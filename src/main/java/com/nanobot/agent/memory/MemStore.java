package com.nanobot.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 长期记忆文件存储（MemStore）。
 *
 * <p>负责 {@code MEMORY.md} 文件的读写操作，是 Agent "长期记忆" 的持久化层。</p>
 *
 * <h3>文件位置</h3>
 * <p>记忆文件固定存放在 {@code <workspace>/memory/MEMORY.md}，
 * 其中 {@code <workspace>} 由构造时传入的工作目录决定。</p>
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li><b>读取</b>：每次对话轮次开始时，通过 {@link #readAll()} 读取完整文件内容，
 *       注入到 system prompt 中，使 Agent 了解历史上下文。</li>
 *   <li><b>写入</b>：当 {@link Consolidator} 生成新的对话摘要后，
 *       通过 {@link #append(String)} 以带时间戳的条目追加到文件末尾。</li>
 * </ol>
 *
 * <h3>文件格式示例</h3>
 * <pre>{@code
 * ## 2026-06-22 14:30
 * 用户讨论了项目架构，决定采用分层设计……
 *
 * ## 2026-06-22 15:10
 * 用户修复了数据库连接问题，关键原因是……
 * }</pre>
 */
public class MemStore {

    private static final Logger log = LoggerFactory.getLogger(MemStore.class);

    /**
     * 时间戳格式化器，用于在每条摘要前标注记录时间。
     * <p>格式为 {@code yyyy-MM-dd HH:mm}，例如 {@code 2026-06-22 14:30}。</p>
     */
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 记忆文件的完整路径（{@code <workspace>/memory/MEMORY.md}）。
     * <p>在构造时确定，后续所有读写操作均基于此路径。</p>
     */
    private final Path memoryFile;

    /**
     * 构造一个 MemStore 实例。
     *
     * <p>根据传入的工作目录，自动拼接出记忆文件路径：
     * {@code <workspace>/memory/MEMORY.md}。
     * 注意：构造时不会创建文件或目录，实际创建在 {@link #append(String)} 时按需执行。</p>
     *
     * @param workspace 项目工作目录的根路径
     */
    public MemStore(Path workspace) {
        this.memoryFile = workspace.resolve("memory").resolve("MEMORY.md");
    }

    // ── 读取操作 ──────────────────────────────────────────────────────────

    /**
     * 读取 MEMORY.md 文件的全部内容。
     *
     * <p>用途：在每次对话轮次开始时调用，将返回的内容注入 system prompt，
     * 让 Agent 能够 "回忆" 之前对话中总结过的关键信息。</p>
     *
     * <p>边界处理：
     * <ul>
     *   <li>文件不存在 → 返回空字符串（首次运行时正常情况）</li>
     *   <li>读取失败 → 记录警告日志并返回空字符串，不影响主流程</li>
     * </ul>
     *
     * @return MEMORY.md 的完整文本内容；若文件不存在或读取失败则返回空字符串
     */
    public String readAll() {
        // 文件不存在时直接返回空串，避免抛出异常
        if (!Files.exists(memoryFile)) return "";
        try {
            return Files.readString(memoryFile);
        } catch (IOException e) {
            log.warn("Failed to read MEMORY.md: {}", e.toString());
            return "";
        }
    }

    // ── 写入操作 ─────────────────────────────────────────────────────────

    /**
     * 将一条摘要追加到 MEMORY.md 文件末尾。
     *
     * <p>每次追加的内容格式为：
     * <pre>{@code
     * ## yyyy-MM-dd HH:mm
     * <摘要内容>
     *
     * }</pre>
     *
     * <p>写入策略：
     * <ul>
     *   <li>{@link StandardOpenOption#CREATE}：文件不存在时自动创建</li>
     *   <li>{@link StandardOpenOption#APPEND}：追加模式写入，不覆盖已有内容</li>
     *   <li>父目录不存在时通过 {@link Files#createDirectories(Path)} 自动创建</li>
     * </ul>
     *
     * @param summary 要追加的摘要文本（会自动去除首尾空白）
     * @throws IOException 若文件写入失败（如磁盘满、权限不足等）
     */
    public void append(String summary) throws IOException {
        // 确保 memory 目录存在，不存在则递归创建
        Files.createDirectories(memoryFile.getParent());

        // 生成当前时间戳，用于标注本次摘要的记录时间
        String timestamp = ZonedDateTime.now().format(FMT);
        // 构建带时间戳的摘要条目（strip() 去除首尾多余空白）
        String entry = String.format("## %s%n%s%n%n", timestamp, summary.strip());

        // 以追加模式写入文件，文件不存在时自动创建
        Files.writeString(memoryFile, entry,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        // 记录日志：摘要超过 80 字符时截断显示，避免日志过长
        log.info("Memory appended: {}", summary.length() > 80
                ? summary.substring(0, 80) + "…" : summary);
    }
}
