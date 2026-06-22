package com.nanobot.agent.memory;

import com.nanobot.provider.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 对话记忆压缩器（Consolidator）。
 *
 * <p>职责：将历史对话消息压缩为一段简短的摘要文本，以便在上下文窗口有限时
 * 保留关键信息，避免丢失重要对话内容。</p>
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li>接收一组需要被压缩的旧消息（通常是较早的对话轮次）。</li>
 *   <li>将消息列表拼接为可读的对话文本（transcript）。</li>
 *   <li>使用专用的 summarisation prompt 调用 LLM，让模型生成一段不超过 300 字的中文摘要。</li>
 *   <li>返回摘要字符串，由调用方（如 {@link MemStore}）持久化到 MEMORY.md，
 *       并在后续会话中注入 system prompt，从而让 Agent "记住" 历史上下文。</li>
 * </ol>
 *
 * <p>注意：摘要阶段的 LLM 调用<b>不携带 tools</b>，防止模型在压缩过程中意外触发工具调用。</p>
 */
public class Consolidator {

    private static final Logger log = LoggerFactory.getLogger(Consolidator.class);

    /**
     * 摘要生成提示词模板。
     * <p>使用 {@code %s} 占位符插入对话记录文本，要求模型：
     * <ul>
     *   <li>用一段话总结关键主题、决定和重要信息</li>
     *   <li>使用中文回复，不超过 300 字</li>
     *   <li>只输出总结内容，不添加额外说明</li>
     * </ul>
     */
    private static final String PROMPT = """
            你是一个记忆整理助手。请阅读以下对话记录，用一段话总结用户和助手讨论的关键主题、决定和重要信息。
            用中文回复，不超过 300 字。只输出总结内容，不要添加额外说明。

            对话记录：
            %s""";

    /** LLM 提供者实例，用于发送摘要请求。 */
    private final LLMProvider llm;

    /**
     * 构造一个 Consolidator 实例。
     *
     * @param llm LLM 提供者，负责实际的模型调用
     */
    public Consolidator(LLMProvider llm) {
        this.llm = llm;
    }

    /**
     * 将给定的消息列表压缩为一段简短的摘要。
     *
     * <p>处理步骤：
     * <ol>
     *   <li><b>转录</b>：调用 {@link #buildTranscript(List)} 将消息列表转为可读文本。</li>
     *   <li><b>构建请求</b>：将转录文本填入 {@link #PROMPT} 模板，构造一个 role=user 的单条消息。</li>
     *   <li><b>调用 LLM</b>：以无 tools 模式调用 {@link LLMProvider#chat(List)}，获取摘要响应。</li>
     *   <li><b>校验返回</b>：若响应内容为空或纯空白，则记录警告并返回 {@code null}。</li>
     * </ol>
     *
     * @param messages 按时间排序的旧消息列表，每条消息为包含 "role" 和 "content" 键的 Map
     * @return 压缩后的摘要文本；若消息为空或摘要生成失败则返回 {@code null}
     */
    public String consolidate(List<Map<String, Object>> messages) {
        // 空消息列表无需压缩，直接返回
        if (messages.isEmpty()) return null;

        // ① 将消息列表转成人类可读的对话文本（格式：[role]: content）
        String transcript = buildTranscript(messages);

        // ② 构建 summarisation prompt：仅包含一条 user 消息，不附带 tools
        //   这样可以防止 LLM 在摘要阶段意外触发工具调用
        List<Map<String, Object>> promptMessages = List.of(
                Map.of("role", "user", "content", String.format(PROMPT, transcript))
        );

        // ③ 调用 LLM 生成摘要（不带 tools）
        try {
            var resp = llm.chat(promptMessages);
            String summary = resp.content();

            // 校验摘要内容：空或纯空白视为失败
            if (summary == null || summary.isBlank()) {
                log.warn("Consolidation returned empty summary");
                return null;
            }
            // 去除首尾多余空白后返回
            return summary.strip();
        } catch (Exception e) {
            // 捕获所有异常（网络超时、API 错误等），记录日志并返回 null
            log.error("Consolidation failed: {}", e.toString());
            return null;
        }
    }

    // ── 辅助方法 ───────────────────────────────────────────────────────

    /**
     * 将消息列表拼接为可读的对话文本。
     *
     * <p>每条消息格式化为 {@code [role]: content}，跳过内容为空的消息
     * （如 tool-call 回显行等无实际文本的消息）。</p>
     *
     * @param messages 消息列表，每条消息为包含 "role" 和 "content" 键的 Map
     * @return 拼接后的对话文本字符串
     */
    private String buildTranscript(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            // 提取角色信息，缺失时默认用 "?"
            String role = String.valueOf(msg.getOrDefault("role", "?"));
            // 提取消息内容，缺失时默认用空字符串
            String content = String.valueOf(msg.getOrDefault("content", ""));
            // 跳过 tool-call 回显行和空消息，减少噪音
            if (content.isBlank()) continue;
            // 拼接为 [role]: content 格式
            sb.append("[").append(role).append("]: ").append(content).append("\n");
        }
        return sb.toString();
    }
}
