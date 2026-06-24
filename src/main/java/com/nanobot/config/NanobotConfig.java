package com.nanobot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Nanobot 类型安全配置类
 *
 * <p>从 {@code application.yml} 的 {@code nanobot} 前缀自动绑定配置属性。
 * 替代散落的 {@code @Value} 注解，提供更好的类型安全和 IDE 支持。</p>
 *
 * <p>使用方式：
 * <pre>
 * {@code @EnableConfigurationProperties(NanobotConfig.class)}
 * public class MyConfig {
 *     public MyConfig(NanobotConfig config) {
 *         String workspace = config.workspace();
 *         int maxRetries = config.llm().maxRetries();
 *     }
 * }
 * </pre>
 */
@ConfigurationProperties(prefix = "nanobot")
public record NanobotConfig(
        /** 工作区根目录：用于存储会话数据、长期记忆等 */
        @DefaultValue("${user.home}/.nanobot") String workspace,

        /** 每次发送给 LLM 的最大对话消息数：防止上下文过长 */
        @DefaultValue("120") int maxMessages,

        /** 自动压缩触发阈值：会话消息数超过此值时触发自动压缩 */
        @DefaultValue("100") int compactThreshold,

        /** 工具调用循环安全上限：防止无限工具调用 */
        @DefaultValue("10") int maxToolRounds,

        /** LLM 提供商配置：包含重试策略等 */
        @DefaultValue LlmConfig llm
) {
    /**
     * LLM 提供商配置
     */
    public record LlmConfig(
            /** 临时失败的最大重试次数（429、503、超时等） */
            @DefaultValue("3") int maxRetries,

            /** 初始重试延迟（毫秒），每次重试翻倍（指数退避） */
            @DefaultValue("1000") long retryDelayMs
    ) {}
}
