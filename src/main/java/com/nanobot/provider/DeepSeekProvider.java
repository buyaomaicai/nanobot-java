package com.nanobot.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek API provider (OpenAI-compatible).
 *
 * <p>Reads credentials from {@code src/main/resources/llm-config.json}.
 * Posts to {@code /v1/chat/completions} with the standard OpenAI request
 * shape, then maps the JSON response to {@link LLMResponse}.</p>
 *
 * <p>Marked {@link Primary} so Spring injects this instead of
 * {@link SimpleLLMProvider} when both are on the classpath.</p>
 */
@Component
@Primary
public class DeepSeekProvider implements LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekProvider.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final LlmConfig config;
    private final WebClient client;

    // ── constructor: load config once, build WebClient ──────────────

    public DeepSeekProvider() {
        this.config = LlmConfig.load();
        this.client = WebClient.builder()
                .baseUrl(config.baseUrl())
                .defaultHeader("Authorization", "Bearer " + config.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("DeepSeekProvider ready  model={}  baseUrl={}  maxTokens={}",
                config.model(), config.baseUrl(), config.maxTokens());
    }

    // ── implementation ──────────────────────────────────────────────

    @Override
    public LLMResponse chat(List<Map<String, Object>> messages) {
        // ① 清洗消息：仅保留 OpenAI API 认可的字段，移除 timestamp 等额外字段
        List<Map<String, Object>> cleanMessages = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("role", msg.get("role"));
            clean.put("content", msg.get("content"));
            // 仅在 tool_calls 非空时包含（空数组会被 API 拒绝）
            Object tc = msg.get("tool_calls");
            if (tc instanceof List<?> list && !list.isEmpty()) {
                clean.put("tool_calls", tc);
            }
            // 仅在存在 tool_call_id 时包含（tool 角色必需）
            if (msg.containsKey("tool_call_id")) {
                clean.put("tool_call_id", msg.get("tool_call_id"));
            }
            cleanMessages.add(clean);
        }

        // ② 构建 OpenAI 兼容请求体
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", cleanMessages);
        body.put("max_tokens", config.maxTokens());
        body.put("temperature", config.temperature());
        body.put("stream", false);

         log.debug("DeepSeek request  model={}  messages={}", config.model(), cleanMessages.size());

        // ② 发送 POST，同步等待结果（Agent 线程本身就是阻塞模型，.block() 合理）
        String raw;
        try {
            raw = client.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("DeepSeek API call failed: {}", e.toString());
            return new LLMResponse("[错误] 调用 DeepSeek API 失败: " + e.getMessage(),
                    List.of(), "error", Map.of());
        }

        log.debug("DeepSeek response  raw={}", raw != null ? raw.substring(0, Math.min(200, raw.length())) : "null");

        // ③ 解析 OpenAI 格式响应 → LLMResponse
        return parseResponse(raw);
    }

    // ── response parsing ────────────────────────────────────────────

    /**
     * Parse the OpenAI-style JSON response.
     *
     * <pre>{@code
     * {
     *   "choices": [{
     *     "message": {"role": "assistant", "content": "..."},
     *     "finish_reason": "stop"
     *   }],
     *   "usage": {"prompt_tokens": 10, "completion_tokens": 20}
     * }
     * }</pre>
     */
    private LLMResponse parseResponse(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);

            // choices[0]
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                log.warn("DeepSeek response has no choices: {}", raw);
                return new LLMResponse("[错误] API 返回空 choices", List.of(), "error", Map.of());
            }

            JsonNode first = choices.get(0);
            String content = first.path("message").path("content").asText("");
            String finishReason = first.path("finish_reason").asText("stop");

            // usage
            JsonNode usageNode = root.get("usage");
            Map<String, Integer> usage = new LinkedHashMap<>();
            if (usageNode != null) {
                usageNode.fieldNames().forEachRemaining(
                        k -> usage.put(k, usageNode.get(k).asInt()));
            }

            // tool calls (if any — for future use)
            List<ToolCallRequest> toolCalls = new ArrayList<>();

            return new LLMResponse(content, toolCalls, finishReason, usage);
        } catch (Exception e) {
            log.error("Failed to parse DeepSeek response: {}", e.toString());
            return new LLMResponse("[错误] 解析 API 响应失败: " + e.getMessage(),
                    List.of(), "error", Map.of());
        }
    }
}
