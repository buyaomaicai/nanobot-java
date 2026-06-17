package com.nanobot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.provider.LLMProvider;
import com.nanobot.provider.LLMResponse;
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

    public AgentLoop(MessageBus bus,
                     LLMProvider llm,
                     SessionManager sessions,
                     ToolRegistry toolRegistry,
                     @Value("${nanobot.workspace}") String workspace) {
        this.bus = bus;
        this.llm = llm;
        this.sessions = sessions;
        this.toolRegistry = toolRegistry;
        this.workspace = workspace;
    }

    // ── main entry ────────────────────────────────────────────────────

    /**
     * Process one inbound message: run the tool-calling loop until the LLM
     * produces a final text answer, then publish the result to outbound.
     */
    public void processOne(InboundMessage msg) throws InterruptedException {
        String sessionKey = msg.getSessionKey();
        Session session = sessions.getOrCreate(sessionKey);

        // ① 记录用户消息
        session.addMessage("user", msg.getContent());

        // ② 获取工具列表（给 LLM 看的"菜单"）
        List<Map<String, Object>> toolDefs = toolRegistry.getDefinitions();

        // ③ tool-calling 循环
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            List<Map<String, Object>> history = session.getHistory(120);
            LLMResponse resp = llm.chat(history, toolDefs);

            // ④ 纯文本回复 — 任务完成
            if (!resp.hasToolCalls()) {
                session.addMessage("assistant", resp.content());
                bus.publishOutbound(OutboundMessage.builder()
                        .channel(msg.getChannel())
                        .chatId(msg.getChatId())
                        .content(resp.content())
                        .build());
                log.debug("Agent complete  session={}  rounds={}", sessionKey, round + 1);
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
}
