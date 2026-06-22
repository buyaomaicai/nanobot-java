package com.nanobot.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = buildBody(messages, tools, false);
        try {
            String raw = client.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return parseResponse(raw);
        } catch (Exception e) {
            log.error("DeepSeek API call failed: {}", e.toString());
            return new LLMResponse("[错误] 调用 DeepSeek API 失败: " + e.getMessage(),
                    List.of(), "error", Map.of());
        }
    }

    // ── request building ───────────────────────────────────────────────

    /**
     * Build the JSON request body.  Shared by chat and chatStream.
     *
     * @param stream  if true, the body includes {@code "stream": true} and
     *                {@code "stream_options": {"include_usage": true}}
     */
    private Map<String, Object> buildBody(List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools,
                                          boolean stream) {
        // ① 清洗消息
        List<Map<String, Object>> cleanMessages = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("role", msg.get("role"));
            clean.put("content", msg.get("content"));
            Object tc = msg.get("tool_calls");
            if (tc instanceof List<?> list && !list.isEmpty()) {
                clean.put("tool_calls", tc);
            }
            if (msg.containsKey("tool_call_id")) {
                clean.put("tool_call_id", msg.get("tool_call_id"));
            }
            cleanMessages.add(clean);
        }

        // ② 构建请求体
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", cleanMessages);
        body.put("max_tokens", config.maxTokens());
        body.put("temperature", config.temperature());
        body.put("stream", stream);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        log.debug("DeepSeek request  model={}  messages={}  tools={}  stream={}",
                config.model(), cleanMessages.size(),
                tools != null ? tools.size() : 0, stream);
        return body;
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

            // tool calls — parse from OpenAI format:
            //   tool_calls: [{ id, type, function: { name, arguments } }]
            //   arguments is a *JSON string*, not a nested object — need double-parse
            List<ToolCallRequest> toolCalls = new ArrayList<>();
            JsonNode toolCallsNode = first.path("message").path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tc : toolCallsNode) {
                    String callId = tc.path("id").asText("");
                    String callName = tc.path("function").path("name").asText("");
                    String argsRaw = tc.path("function").path("arguments").asText("{}");
                    try {
                        Map<String, Object> args = mapper.readValue(argsRaw,
                                new com.fasterxml.jackson.core.type.TypeReference<>() {});
                        toolCalls.add(new ToolCallRequest(callId, callName, args));
                    } catch (Exception e) {
                        log.warn("Failed to parse tool-call arguments for {}: {}", callName, e.toString());
                    }
                }
            }

            return new LLMResponse(content, toolCalls, finishReason, usage);
        } catch (Exception e) {
            log.error("Failed to parse DeepSeek response: {}", e.toString());
            return new LLMResponse("[错误] 解析 API 响应失败: " + e.getMessage(),
                    List.of(), "error", Map.of());
        }
    }

    // ── streaming ──────────────────────────────────────────────────────

    /**
     * SSE（Server-Sent Events）流式对话实现。
     *
     * <p>整体流程：构建请求体 → 发送HTTP请求 → 逐行读取SSE事件流 →
     * 解析每个data片段 → 通过callback实时回调 → 流结束后组装完整响应</p>
     *
     * <p>SSE协议格式：服务端每条数据以"data: "开头，最后发送"data: [DONE]"表示结束。</p>
     */
    @Override
    public void chatStream(List<Map<String, Object>> messages,   // 对话历史：[{"role":"user","content":"..."}]
                           List<Map<String, Object>> tools,      // 可用工具列表：[{"type":"function","function":{...}}]
                           StreamCallback callback) {            // 流式回调：onToken/onComplete/onError

        // ━━━ 第一阶段：构建请求 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 调用buildBody构建请求体，stream=true表示要流式响应
        Map<String, Object> body = buildBody(messages, tools, true);
        String bodyJson;
        try {
            // 将Map序列化为JSON字符串，作为HTTP请求体
            bodyJson = mapper.writeValueAsString(body);
        } catch (Exception e) {
            // 序列化失败，直接回调onError通知调用方
            callback.onError(e);
            return;
        }

        // ━━━ 第二阶段：创建HTTP客户端并发送请求 ━━━━━━━━━━━━━━━━━━━━━━━━
        // 使用JDK 11+自带的HttpClient（不用第三方库），设置30秒连接超时
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))  // 连接超时30秒，防止网络卡死
                .build();
        try {
            // 构建HTTP POST请求
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/v1/chat/completions"))  // DeepSeek API端点
                    .header("Authorization", "Bearer " + config.apiKey())         // API密钥认证
                    .header("Content-Type", "application/json")                    // 请求体是JSON
                    .header("Accept", "text/event-stream")                        // 告诉服务器我们要SSE流式响应
                    .timeout(java.time.Duration.ofSeconds(120))                   // 整个请求最多等120秒
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))  // 发送JSON请求体
                    .build();

            // 发送请求，以InputStream方式接收响应体（流式读取，不会一次性加载全部数据到内存）
            HttpResponse<java.io.InputStream> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());

            log.info("SSE stream opened  status={}", resp.statusCode());

            // ━━━ 第三阶段：检查响应状态 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 如果状态码不是200，说明请求失败（如API Key错误、参数错误等）
            if (resp.statusCode() != 200) {
                String errBody;
                try (var is = resp.body()) {
                    // 读取错误响应体（如 {"error":{"message":"...","type":"..."}}）
                    errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
                log.error("SSE request failed  status={}  body={}", resp.statusCode(), errBody);
                throw new RuntimeException("SSE HTTP " + resp.statusCode() + ": " + errBody);
            }

            // ━━━ 第四阶段：初始化累加器 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 这些变量用来在流式读取过程中逐步积累数据，最终拼成完整的LLMResponse
            StringBuilder contentBuf = new StringBuilder();    // 累加所有文本片段，最终得到完整的AI回复文本
            List<ToolCallRequest> toolCalls = new ArrayList<>(); // 最终的工具调用列表
            String finishReason = "stop";                       // 结束原因，默认"stop"（正常结束），也可能是"tool_calls"
            Map<String, Integer> usage = Map.of();             // token用量统计（prompt_tokens, completion_tokens等）

            // tool-call的流式累加器：因为工具调用是分多个chunk发送的，需要逐步拼接
            // key=工具调用的index，value=该工具调用的拼接器
            // 例如：第一个chunk发id和name，后续chunk发arguments的片段
            Map<Integer, ToolCallBuilder> tcBuilders = new LinkedHashMap<>();

            // ━━━ 第五阶段：逐行读取SSE事件流 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 用BufferedReader逐行读取InputStream，SSE协议是文本行协议
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;       // 当前读取的行
                int lineCount = 0;  // 行号计数器，用于日志定位

                // 逐行读取，直到流结束（readLine返回null）
                while ((line = reader.readLine()) != null) {
                    lineCount++;

                    // 跳过空行（SSE协议中空行是事件分隔符，对我们没用）
                    if (line.isBlank()) continue;

                    // 跳过非data行（SSE可能还有event:、id:、retry:等行，我们只关心data:）
                    if (!line.startsWith("data: ")) {
                        log.debug("SSE skip line #{}: {}", lineCount, line.substring(0, Math.min(60, line.length())));
                        continue;
                    }

                    // 去掉"data: "前缀，得到实际的JSON数据
                    // 例如："data: {\"choices\":[...]}" → "{\"choices\":[...]}"
                    String data = line.substring(6).strip();
                    log.debug("SSE chunk #{} ({} chars): {}", lineCount, data.length(), data);

                    // DeepSeek用"data: [DONE]"表示流结束，收到后跳出循环
                    if ("[DONE]".equals(data)) {
                        log.info("SSE stream ended ({} lines)", lineCount);
                        break;
                    }

                    // ── 解析单个SSE chunk ──────────────────────────────────
                    try {
                        // 将data部分解析为JSON树
                        JsonNode chunk = mapper.readTree(data);

                        // 获取choices数组（OpenAI兼容格式，通常只有一个choice）
                        JsonNode choices = chunk.get("choices");
                        if (choices != null && choices.isArray() && !choices.isEmpty()) {

                            // 取第一个choice（几乎总是只有一个）
                            // 流式响应中用"delta"而非"message"，delta表示"增量"（这一小块新内容）
                            JsonNode delta = choices.get(0).get("delta");
                            if (delta != null) {

                                // ── 处理文本增量 ──────────────────────────
                                // delta.content 就是这次新到的文本片段
                                // DeepSeek会在第一个chunk发content:""（空字符串），这里过滤掉
                                String text = delta.path("content").asText(null);
                                if (text != null && !text.isEmpty()) {
                                    contentBuf.append(text);   // 累加到完整文本缓冲
                                    callback.onToken(text);     // 实时回调：把这段文字推给前端显示（打字机效果）
                                }

                                // ── 处理工具调用增量 ──────────────────────
                                // 流式模式下，工具调用是分多个chunk发送的：
                                //   chunk1: tool_calls[0] = {index:0, id:"call_xxx", function:{name:"read_file"}}
                                //   chunk2: tool_calls[0] = {index:0, function:{arguments:"{\"pa"}}
                                //   chunk3: tool_calls[0] = {index:0, function:{arguments:"th\":\"/tm"}}
                                //   ...直到arguments拼完
                                JsonNode tcNode = delta.path("tool_calls");
                                if (tcNode.isArray()) {
                                    for (JsonNode tc : tcNode) {
                                        // index标识这是第几个工具调用（一次回复可能调用多个工具）
                                        int idx = tc.path("index").asInt(0);
                                        // 根据index获取或创建对应的拼接器
                                        ToolCallBuilder b = tcBuilders.computeIfAbsent(idx,
                                                k -> new ToolCallBuilder());
                                        // 第一个chunk会带id（如"call_abc123"），后续chunk不带
                                        if (!tc.path("id").asText("").isEmpty())
                                            b.id = tc.path("id").asText();
                                        // 解析function子对象
                                        JsonNode fn = tc.path("function");
                                        // 第一个chunk会带工具名（如"read_file"），后续chunk不带
                                        if (!fn.path("name").asText("").isEmpty())
                                            b.name = fn.path("name").asText();
                                        // arguments是逐步到达的JSON片段，拼接到StringBuilder
                                        // 最终拼成一个完整的JSON字符串如："{\"path\":\"/tmp/a.txt\"}"
                                        String argsFrag = fn.path("arguments").asText(null);
                                        if (argsFrag != null)
                                            b.argsBuf.append(argsFrag);
                                    }
                                }
                            }

                            // ── 处理结束原因 ────────────────────────────
                            // finish_reason在最后一个chunk才会出现，值为"stop"（正常结束）
                            // 或"tool_calls"（AI要求调用工具）
                            String fr = choices.get(0).path("finish_reason").asText(null);
                            if (fr != null) {
                                finishReason = fr;
                            }
                        }

                        // ── 处理token用量 ────────────────────────────────
                        // usage通常只在最后一个chunk出现，包含prompt_tokens和completion_tokens
                        JsonNode usageNode = chunk.get("usage");
                        if (usageNode != null) {
                            Map<String, Integer> u = new LinkedHashMap<>();
                            usageNode.fieldNames().forEachRemaining(
                                    k -> u.put(k, usageNode.get(k).asInt()));
                            usage = u;
                        }
                    } catch (Exception e) {
                        // 单个chunk解析失败不中断整个流，跳过即可（可能是网络抖动导致的不完整JSON）
                        log.debug("Skip malformed SSE line: {}", line.substring(0, Math.min(80, line.length())));
                    }
                } // end while — SSE流读取完毕
            } // end try-with-resources — reader自动关闭

            // ━━━ 第六阶段：组装工具调用结果 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 流式传输时，工具调用的arguments是被切成碎片发来的，现在已经全部拼好了
            // 这里把每个ToolCallBuilder转成最终的ToolCallRequest对象
            for (ToolCallBuilder b : tcBuilders.values()) {
                try {
                    // 把拼接好的arguments字符串解析为Map（如 {"path":"/tmp/a.txt"} → Map）
                    Map<String, Object> args = mapper.readValue(b.argsBuf.toString(),
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    toolCalls.add(new ToolCallRequest(b.id, b.name, args));
                } catch (Exception e) {
                    // arguments JSON拼接失败（理论上不应该发生，但防御性编程）
                    log.warn("Failed to parse accumulated tool args for {}: {}", b.name, e.toString());
                }
            }

            // ━━━ 第七阶段：回调完成 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 把流式过程中积累的所有数据打包成LLMResponse，通过onComplete回调
            callback.onComplete(new LLMResponse(
                    contentBuf.toString(),  // 完整的AI回复文本
                    toolCalls,               // 工具调用列表（可能为空）
                    finishReason,            // "stop" 或 "tool_calls"
                    usage));                 // token用量统计

        } catch (Exception e) {
            // 任何未捕获的异常（网络断开、超时等）都通过onError回调
            callback.onError(e);
        }
    }

    // ── inner types ────────────────────────────────────────────────────

    /**
     * 工具调用的流式拼接器。
     *
     * <p>流式模式下，一个工具调用会被拆成多个chunk发送：
     *   - 第一个chunk带id和name
     *   - 后续chunk带arguments的片段
     * 这个类负责把这些碎片逐步拼回完整。</p>
     *
     * <p>示例——3个chunk拼接一个工具调用：</p>
     * <pre>
     *   chunk1 → id="call_abc", name="read_file", args=""
     *   chunk2 →                              args="{\"path\":\"/tm"
     *   chunk3 →                              args="p/a.txt\"}"
     *   最终结果 → ToolCallRequest(id="call_abc", name="read_file", args={path="/tmp/a.txt"})
     * </pre>
     */
    private static class ToolCallBuilder {
        String id = "";                           // 工具调用的唯一标识，如"call_abc123"
        String name = "";                         // 工具名称，如"read_file"、"shell"
        final StringBuilder argsBuf = new StringBuilder();  // 工具参数的JSON片段拼接缓冲区
    }
}
