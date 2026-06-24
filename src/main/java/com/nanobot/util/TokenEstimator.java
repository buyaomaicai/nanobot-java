package com.nanobot.util;

import java.util.List;
import java.util.Map;

/**
 * 粗略的 Token 数量估算器
 *
 * <p>准确的 tokenization 需要模型特定的 token 器（如 tiktoken）。
 * 在 Java 教学项目中，我们使用启发式方法：
 * <ul>
 *   <li>英文：约 4 个字符/token</li>
 *   <li>中日韩文字：约 1.5 个字符/token</li>
 * </ul>
 * 这足以用于预算门控决策，避免引入 jtokkit 的 20 MB+ 本地库依赖。</p>
 *
 * <p>使用场景：
 * <ul>
 *   <li>估算对话是否超出 LLM 上下文窗口</li>
 *   <li>决定是否触发自动压缩</li>
 * </ul>
 */
public final class TokenEstimator {

    // 私有构造函数，防止实例化（工具类）
    private TokenEstimator() {}

    /**
     * 估算单条消息的 token 数量
     * @param message 消息对象（必须包含 "content" 字段）
     * @return 估算的 token 数量
     */
    public static int estimate(Map<String, Object> message) {
        String content = String.valueOf(message.getOrDefault("content", ""));
        return estimate(content);
    }

    /**
     * 估算消息列表的总 token 数量
     * @param messages 消息列表
     * @return 估算的总 token 数量
     */
    public static int estimate(List<Map<String, Object>> messages) {
        return messages.stream().mapToInt(TokenEstimator::estimate).sum();
    }

    /**
     * 估算纯文本的 token 数量
     * @param text 待估算的文本
     * @return 估算的 token 数量
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0, latin = 0;
        // 遍历每个字符，统计中日韩文字和拉丁字符数量
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) {
                cjk++;  // 中日韩文字：约 1 字符/token
            } else {
                latin++;  // 拉丁字符：约 4 字符/token
            }
        }
        // 计算公式：(CJK字符 * 1.0 + 拉丁字符 / 4.0) * 1.1（10% 额外开销用于特殊 token）
        return (int) ((cjk * 1.0 + latin / 4.0) * 1.1);
    }
}
