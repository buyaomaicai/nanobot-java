package com.nanobot.command;

import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

/**
 * 斜杠命令路由器：负责分发和执行所有以 / 开头的命令
 *
 * <p>支持两种匹配模式（按优先级排序）：
 * <ol>
 *   <li><b>精确匹配</b> — 完整匹配命令名（如 /stop、/help）</li>
 *   <li><b>前缀匹配</b> — 按最长前缀优先匹配（如 /team add …）</li>
 * </ol>
 *
 * <p>命令处理器返回 {@link OutboundMessage} 发送给用户，
 * 或返回 {@code null} 表示无需发送响应。</p>
 */
@Component
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    // 精确匹配表：key=命令名, value=处理器函数
    private final Map<String, Function<CommandContext, OutboundMessage>> exact = new LinkedHashMap<>();
    // 前缀匹配列表：按前缀长度降序排列，确保最长前缀优先匹配
    private final List<Map.Entry<String, Function<CommandContext, OutboundMessage>>> prefix = new ArrayList<>();

    // ── 注册方法 ──────────────────────────────────────────────────

    /**
     * 注册精确匹配的命令
     * @param key 命令名（不含 / 前缀，如 "compact"）
     * @param handler 命令处理器函数
     */
    public CommandRouter register(String key, Function<CommandContext, OutboundMessage> handler) {
        exact.put(key.toLowerCase(), handler);  // 统一转小写，实现大小写不敏感
        return this;
    }

    /**
     * 注册前缀匹配的命令
     * @param pfx 命令前缀（如 "team" 可匹配 /team add、/team remove）
     * @param handler 命令处理器函数
     */
    public CommandRouter registerPrefix(String pfx,
                                         Function<CommandContext, OutboundMessage> handler) {
        prefix.add(Map.entry(pfx.toLowerCase(), handler));
        // 按前缀长度降序排序，确保最长前缀优先匹配
        prefix.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        return this;
    }

    // ── 命令分发 ──────────────────────────────────────────────────────

    /** 判断给定文本是否为已注册的斜杠命令 */
    public boolean isCommand(String text) {
        // 必须以 / 开头
        if (text == null || !text.startsWith("/")) return false;
        String key = commandKey(text);
        // 检查精确匹配
        if (exact.containsKey(key)) return true;
        // 检查前缀匹配
        for (var e : prefix) {
            if (key.startsWith(e.getKey())) return true;
        }
        return false;
    }

    /**
     * 分发命令并返回响应消息
     * @param text 用户输入的完整文本（如 "/compact"）
     * @param ctx 命令上下文
     * @return 响应消息，如果命令未识别则返回 null
     */
    public OutboundMessage dispatch(String text, CommandContext ctx) {
        String key = commandKey(text);

        // 先查找精确匹配
        Function<CommandContext, OutboundMessage> handler = exact.get(key);
        if (handler == null) {
            // 未找到精确匹配，尝试前缀匹配
            for (var e : prefix) {
                if (key.startsWith(e.getKey())) {
                    handler = e.getValue();
                    break;
                }
            }
        }
        if (handler == null) return null;  // 命令未识别

        // 执行命令处理器
        try {
            return handler.apply(ctx);
        } catch (Exception e) {
            log.warn("Command '{}' failed: {}", key, e.toString());
            return OutboundMessage.builder()
                    .channel(ctx.msg().getChannel())
                    .chatId(ctx.msg().getChatId())
                    .content("[错误] 命令执行失败: " + e.getMessage())
                    .build();
        }
    }

    // ── 辅助方法 ───────────────────────────────────────────────────────

    /**
     * 提取命令名称：从用户输入中提取命令 key
     * @param text 用户输入（如 "/compact arg1 arg2"）
     * @return 命令名（如 "compact"），统一转小写
     */
    private static String commandKey(String text) {
        String trimmed = text.strip().toLowerCase();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(1, space) : trimmed.substring(1);
    }
}
