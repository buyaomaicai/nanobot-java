package com.nanobot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.agent.memory.Consolidator;
import com.nanobot.agent.memory.MemStore;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.MessageConstants;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.provider.LLMProvider;
import com.nanobot.provider.LLMResponse;
import com.nanobot.provider.StreamCallback;
import com.nanobot.provider.ToolCallRequest;
import com.nanobot.session.Session;
import com.nanobot.session.SessionManager;
import com.nanobot.tool.Tool;
import com.nanobot.tool.ToolContext;
import com.nanobot.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core agent loop — the bridge between LLM decisions and Tool execution.
 *
 * <h3>How it works</h3>
 * <pre>
 *   用户消息 → session.addMessage("user")
 *     └─ loop ─────────────────────────┐
 *        ├─ 调 LLM（带历史 + tools）      │
 *        ├─ 纯文本？→ 发回复，结束          │
 *        └─ 有 tool_calls？               │
 *             ├─ 执行每个 tool             │
 *             ├─ tool 结果写入 session     │
 *             └─ 回到 loop 顶部 ──────────┘
 * </pre>
 *
 * <p>Mirrors {@code nanobot/agent/runner.py :: AgentRunner}.</p>
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final ObjectMapper mapper = new ObjectMapper();//json化工具

    /** Safety cap — prevent infinite tool-calling loops. */
    private static final int MAX_TOOL_ROUNDS = 10;

    private final MessageBus bus;
    private final LLMProvider llm;
    private final SessionManager sessions;
    private final ToolRegistry toolRegistry;
    private final String workspace;
    private final ContextBuilder ctxBuilder;
    private final MemStore memStore;
    private final Consolidator consolidator;

    /** Auto-compact when session messages exceed this count. */
    private static final int COMPACT_THRESHOLD = 100;

    public AgentLoop(MessageBus bus,
                     LLMProvider llm,
                     SessionManager sessions,
                     ToolRegistry toolRegistry,
                     ContextBuilder ctxBuilder,
                     MemStore memStore,
                     @Value("${nanobot.workspace}") String workspace) {
        this.bus = bus;
        this.llm = llm;
        this.sessions = sessions;
        this.toolRegistry = toolRegistry;
        this.ctxBuilder = ctxBuilder;
        this.memStore = memStore;
        this.workspace = workspace;
        this.consolidator = new Consolidator(llm);
    }

    // ── main entry ────────────────────────────────────────────────────

    /**
     * Process one inbound message: run the tool-calling loop until the LLM
     * produces a final text answer, then publish the result to outbound.
     */
    public void processOne(InboundMessage msg) throws InterruptedException {
        String sessionKey = msg.getSessionKey();
        Session session = sessions.getOrCreate(sessionKey);

        String userContent = msg.getContent();

        // ── /compact 命令：手动触发记忆压缩 ──────────────
        if ("/compact".equals(userContent)) {
            handleCompact(session, msg);
            return;
        }

        // ① 记录用户消息
        session.addMessage("user", userContent);

        // ② 获取工具列表（给 LLM 看的"菜单"）
        List<Map<String, Object>> toolDefs = toolRegistry.getDefinitions();

        // ③ tool-calling 循环（全部使用 streaming 模式）
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            List<Map<String, Object>> history = augmentWithMemory(session.getHistory(120));

            // 用数组做可变容器（lambda 内不能改局部变量）
            LLMResponse[] resultHolder = new LLMResponse[1];

            llm.chatStream(history, toolDefs, new StreamCallback() {
                @Override
                public void onToken(String text) {
                    log.debug("onToken ({} chars): {}", text.length(),
                            text.length() > 50 ? text.substring(0, 50) + "…" : text);
                    try {
                        bus.publishOutbound(OutboundMessage.builder()
                                .channel(msg.getChannel())
                                .chatId(msg.getChatId())
                                .content(text)
                                .metadata(Map.of(
                                        MessageConstants.OUTBOUND_META_STREAM_PROGRESS, true))
                                .build());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                public void onComplete(LLMResponse response) {
                    resultHolder[0] = response;
                }

                @Override
                public void onError(Throwable error) {
                    log.warn("Streaming failed, falling back to non-streaming: {}", error.toString());
                    try {
                        LLMResponse fallback = llm.chat(history, toolDefs);
                        // fallback succeeded: send the full content as one "token" event
                        if (fallback.content() != null && !fallback.content().isEmpty()) {
                            onToken(fallback.content());
                        }
                        resultHolder[0] = fallback;
                    } catch (Exception e) {
                        log.error("Fallback chat also failed", e);
                    }
                }
            });

            LLMResponse resp = resultHolder[0];
            if (resp == null) {
                log.error("chatStream completed without response");
                return;
            }

            // ④ 纯文本回复 — finish_reason 分派
            if (!resp.hasToolCalls()) {
                String fr = resp.finishReason();

                // length — 输出被 max_tokens 截断，自动续写
                if ("length".equals(fr) && round < MAX_TOOL_ROUNDS - 1) {
                    log.debug("Output truncated, requesting continuation (round {})", round);
                    session.addMessage("assistant", resp.content());
                    session.addMessage("user",
                            "请继续，从你刚才被打断的地方接着写。不要重复已经写过的内容。");
                    continue;
                }

                // content_filter — 内容被安全策略拦截
                if ("content_filter".equals(fr)) {
                    String fallback = "（内容因安全策略被过滤，请尝试换一种表述方式）";
                    session.addMessage("assistant", fallback);
                    bus.publishOutbound(OutboundMessage.builder()
                            .channel(msg.getChannel()).chatId(msg.getChatId())
                            .content(fallback).build());
                    return;
                }

                // stop / error / 其他 — 正常结束
                session.addMessage("assistant",
                        resp.content() != null ? resp.content() : "");
                bus.publishOutbound(OutboundMessage.builder()
                        .channel(msg.getChannel()).chatId(msg.getChatId())
                        .content("").build());  // stream end signal
                log.debug("Agent complete  session={}  rounds={}  finish={}",
                        sessionKey, round + 1, fr);
                maybeAutoCompact(session);
                return;
            }

            // ⑤ LLM 要求调用工具 — 记录 assistant 消息（含 tool_calls）
            session.addMessage("assistant",
                    resp.content() != null ? resp.content() : "",
                    assistantKwargs(resp.toolCalls()));

            // ⑥ 逐个执行工具
            for (ToolCallRequest tc : resp.toolCalls()) {
                Tool tool = toolRegistry.get(tc.name());
                String result;
                if (tool != null) {
                    log.debug("Executing tool: {}  args={}", tc.name(), tc.arguments());
                    result = tool.execute(tc.arguments(), new ToolContext(workspace));
                } else {
                    result = "[错误] 未找到工具：" + tc.name();
                }
                // ⑦ 把工具结果写回 session
                session.addMessage("tool", result, toolKwargs(tc));
            }
            // 循环继续 — LLM 下次会看到 tool results 并决定下一步
        }

        // ⑧ 达到最大循环次数 — 强制终止
        log.warn("Agent hit MAX_TOOL_ROUNDS ({}), forcing stop", MAX_TOOL_ROUNDS);
        String fallback = "（达到最大工具调用次数，处理中断）";
        session.addMessage("assistant", fallback);
        bus.publishOutbound(OutboundMessage.builder()
                .channel(msg.getChannel())
                .chatId(msg.getChatId())
                .content(fallback)
                .build());
    }

    // ── helpers ───────────────────────────────────────────────────────

    /**
     * Convert tool-call list to OpenAI-format maps for session storage.
     * The stored format matches what DeepSeek expects when replaying history.
     */
    private Map<String, Object> assistantKwargs(List<ToolCallRequest> calls) {
        List<Map<String, Object>> openaiCalls = new ArrayList<>();
        for (ToolCallRequest tc : calls) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tc.name());
            try {
                fn.put("arguments", mapper.writeValueAsString(tc.arguments()));
            } catch (Exception e) {
                fn.put("arguments", "{}");
            }

            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", tc.id());
            call.put("type", "function");
            call.put("function", fn);
            openaiCalls.add(call);
        }

        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("tool_calls", openaiCalls);
        return kwargs;
    }

    /** Build session kwargs for a tool-result message. */
    private Map<String, Object> toolKwargs(ToolCallRequest tc) {
        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("tool_call_id", tc.id());
        kwargs.put("name", tc.name());
        return kwargs;
    }

    // ── memory ─────────────────────────────────────────────────────────

    /** Build the full system prompt and prepend it to the conversation history. */
    private List<Map<String, Object>> augmentWithMemory(List<Map<String, Object>> history) {
        String systemPrompt = ctxBuilder.build();
        List<Map<String, Object>> augmented = new ArrayList<>(history.size() + 1);
        augmented.add(Map.of("role", "system", "content", systemPrompt));
        augmented.addAll(history);
        return augmented;
    }

    /** Compress conversation into long-term memory.  {@code msg} is null during auto-compact. */
    private void handleCompact(Session session, InboundMessage msg) throws InterruptedException {
        List<Map<String, Object>> all = session.getMessages();
        if (all.isEmpty()) return;

        List<Map<String, Object>> oldMessages = new ArrayList<>(all);
        String summary = consolidator.consolidate(oldMessages);
        if (summary == null) {
            reply(msg, "[错误] 记忆压缩失败，请稍后重试。");
            return;
        }

        try {
            memStore.append(summary);
        } catch (IOException e) {
            log.error("Failed to write memory: {}", e.toString());
        }
        session.markConsolidated();
        log.info("Compacted {} messages → {} chars of memory", all.size(), summary.length());

        reply(msg, "已压缩 " + all.size() + " 条对话为长期记忆。");
    }

    /** Send a reply to the user if {@code msg} is available (null during auto-compact). */
    private void reply(InboundMessage msg, String text) throws InterruptedException {
        if (msg == null) return;
        bus.publishOutbound(OutboundMessage.builder()
                .channel(msg.getChannel()).chatId(msg.getChatId())
                .content(text).build());
    }

    /** Auto-compact when the session grows beyond the threshold. */
    private void maybeAutoCompact(Session session) {
        if (session.getMessages().size() > COMPACT_THRESHOLD) {
            log.info("Auto-compacting session {} ({} messages)", session.getKey(), session.getMessages().size());
            // fire-and-forget via a daemon thread to avoid blocking the agent loop
            var s = session;
            new Thread(() -> {
                try {
                    handleCompact(s, null);
                } catch (Exception e) {
                    log.warn("Auto-compact failed: {}", e.toString());
                }
            }, "nanobot-compact").start();
        }
    }
}
