package com.nanobot.command;

import com.nanobot.agent.memory.Consolidator;
import com.nanobot.agent.memory.MemStore;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.provider.LLMProvider;
import com.nanobot.session.Session;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内置命令注册器：在应用启动时注册所有内置斜杠命令
 *
 * <p>支持的命令：
 * <ul>
 *   <li>/compact - 压缩当前对话为长期记忆</li>
 *   <li>/clear - 清空当前会话消息</li>
 *   <li>/stop - 停止当前会话</li>
 *   <li>/help - 显示帮助信息</li>
 * </ul>
 */
@Component
public class BuiltinCommands {

    private static final Logger log = LoggerFactory.getLogger(BuiltinCommands.class);

    // 命令路由器：用于注册命令处理器
    private final CommandRouter router;
    // 长期记忆存储：用于保存压缩后的对话摘要
    private final MemStore memStore;
    // 对话压缩器：调用 LLM 将对话压缩为摘要
    private final Consolidator consolidator;

    /**
     * 构造函数：注入依赖并初始化压缩器
     */
    public BuiltinCommands(CommandRouter router,
                           MemStore memStore,
                           LLMProvider llm) {
        this.router = router;
        this.memStore = memStore;
        this.consolidator = new Consolidator(llm);
    }

    /**
     * 在 Spring 初始化后自动执行，注册所有内置命令
     */
    @PostConstruct
    void register() {
        // 注册 /compact 命令：压缩对话为长期记忆
        router.register("compact", ctx -> handleCompact(ctx.session()));
        // 注册 /clear 命令：清空会话
        router.register("clear",   ctx -> handleClear(ctx.session()));
        // 注册 /stop 命令：停止会话
        router.register("stop",    ctx -> OutboundMessage.builder()
                .channel(ctx.msg().getChannel()).chatId(ctx.msg().getChatId())
                .content("会话已停止。输入新消息开始新一轮对话。").build());
        // 注册 /help 命令：显示帮助信息
        router.register("help",    ctx -> OutboundMessage.builder()
                .channel(ctx.msg().getChannel()).chatId(ctx.msg().getChatId())
                .content("""
                    可用命令:
                    /compact — 压缩当前对话为长期记忆
                    /clear   — 清空当前会话消息
                    /stop    — 停止当前会话
                    /help    — 显示此帮助
                    /exit    — 退出程序""").build());
    }

    // ── 命令处理器 ──────────────────────────────────────────────────────

    /**
     * 处理 /compact 命令：压缩对话为长期记忆
     * @param session 当前会话
     * @return 响应消息
     */
    private OutboundMessage handleCompact(Session session) {
        List<Map<String, Object>> all = session.getMessages();
        if (all.isEmpty()) {
            return reply(session, "当前没有需要压缩的对话内容。");
        }
        // 调用 LLM 压缩对话
        String summary = consolidator.consolidate(new ArrayList<>(all));
        if (summary == null) {
            return reply(session, "[错误] 记忆压缩失败，请稍后重试。");
        }
        // 保存到长期记忆
        try {
            memStore.append(summary);
        } catch (IOException e) {
            log.error("Failed to write memory: {}", e.toString());
        }
        // 标记已压缩
        session.markConsolidated();
        log.info("Compacted {} messages → {} chars", all.size(), summary.length());
        return reply(session, "已压缩 " + all.size() + " 条对话为长期记忆。");
    }

    /**
     * 处理 /clear 命令：清空会话
     * @param session 当前会话
     * @return 响应消息
     */
    private OutboundMessage handleClear(Session session) {
        session.clear();
        return reply(session, "会话已清空。");
    }

    /**
     * 构建响应消息的辅助方法
     * @param session 当前会话
     * @param text 响应文本
     * @return 响应消息对象
     */
    private static OutboundMessage reply(Session session, String text) {
        return OutboundMessage.builder()
                .channel("cli").chatId("default").content(text).build();
    }
}
