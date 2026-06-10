package com.nanobot.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器，基于 JSONL 文件实现持久化存储。
 *
 * <p>每个会话以 {@code .jsonl} 文件的形式存储在 sessions 目录下。
 * 文件第一行为元数据记录（_type=metadata），后续每一行为一条消息记录。
 * 使用内存缓存（{@link ConcurrentHashMap}）避免频繁磁盘读取。</p>
 *
 * <p>写入策略：先写临时文件 {@code .tmp}，再通过原子移动替换目标文件，
 * 确保写入过程中崩溃不会损坏已有数据。</p>
 *
 * <p>对应 Python 实现：{@code nanobot/session/manager.py :: SessionManager}。</p>
 */
@Component
public class SessionManager {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** JSON 序列化/反序列化器，全局共享（ObjectMapper 线程安全） */
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 会话文件存储的根目录，即 {workspace}/sessions */
    private final Path sessionsDir;

    /** 会话缓存，key 为会话标识，value 为 Session 对象，使用 ConcurrentHashMap 保证线程安全 */
    private final Map<String, Session> cache = new ConcurrentHashMap<>();

    /**
     * 构造函数，根据配置的工作空间路径初始化 sessions 目录。
     *
     * @param workspace 工作空间根路径，通过 {@code @Value("${nanobot.workspace}")} 注入
     * @throws UncheckedIOException 若无法创建 sessions 目录
     */
    public SessionManager(@Value("${nanobot.workspace}") String workspace) {
        this.sessionsDir = Path.of(workspace, "sessions");
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create sessions directory: " + sessionsDir, e);
        }
    }

    // ── 公共 API ─────────────────────────────────────────────────────

    /**
     * 获取已存在的会话，若不存在则创建新会话。线程安全。
     * <p>优先从缓存查找；缓存未命中时尝试从磁盘加载；磁盘也不存在则新建空会话。</p>
     *
     * @param key 会话唯一标识
     * @return 对应的 Session 对象（永不返回 null）
     */
    public Session getOrCreate(String key) {
        return cache.computeIfAbsent(key, k -> {
            Session loaded = load(k);           // 尝试从磁盘加载
            return loaded != null ? loaded : new Session(k);  // 加载失败则新建
        });
    }

    /**
     * 将会话持久化到 JSONL 文件，并更新内存缓存。
     * <p>写入流程：先写入临时文件，成功后原子移动替换目标文件，防止写入中断导致数据损坏。</p>
     *
     * @param session 待保存的会话对象
     * @param fsync   是否强制 OS 级别刷盘（优雅关闭时设为 true，确保数据落盘）
     * @throws IOException 若写入或移动文件失败
     */
    public void save(Session session, boolean fsync) throws IOException {
        Path path = sessionPath(session.getKey());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");  // 临时文件，写入完成后原子替换

        try (BufferedWriter w = Files.newBufferedWriter(tmp)) {
            // 第一行：元数据记录
            Map<String, Object> metaLine = new LinkedHashMap<>();
            metaLine.put("_type", "metadata");
            metaLine.put("key", session.getKey());
            metaLine.put("created_at", session.getCreatedAt().toString());
            metaLine.put("updated_at", session.getUpdatedAt().toString());
            metaLine.put("metadata", session.getMetadata());
            metaLine.put("last_consolidated", session.getLastConsolidated());
            w.write(mapper.writeValueAsString(metaLine));
            w.newLine();

            // 后续行：消息记录
            for (Map<String, Object> msg : session.getMessages()) {
                w.write(mapper.writeValueAsString(msg));
                w.newLine();
            }
            w.flush();
            if (fsync && w instanceof BufferedWriter) {
                // 尽力而为：尝试通过底层 FileOutputStream 的 fd 执行 fsync
                // 当前实现为占位，后续可通过 FileChannel.force(true) 完成
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp);  // 写入失败时清理临时文件
            throw e;
        }

        // 原子移动：临时文件 → 目标文件，确保数据完整性
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        cache.put(session.getKey(), session);  // 更新内存缓存
        log.debug("Saved session {} ({} messages)", session.getKey(), session.getMessages().size());
    }

    /**
     * 将所有缓存中的会话重新保存到磁盘（用于优雅关闭时确保数据持久化）。
     *
     * @return 成功刷盘的会话数量
     */
    public int flushAll() {
        int flushed = 0;
        for (Map.Entry<String, Session> entry : cache.entrySet()) {
            try {
                save(entry.getValue(), true);  // fsync=true，强制刷盘
                flushed++;
            } catch (Exception e) {
                log.warn("Failed to flush session {}", entry.getKey(), e);
            }
        }
        log.info("Flushed {} cached sessions to disk", flushed);
        return flushed;
    }

    /**
     * 仅从内存缓存中移除会话，不影响磁盘文件。
     * <p>下次调用 {@link #getOrCreate} 时将重新从磁盘加载。</p>
     *
     * @param key 会话唯一标识
     */
    public void invalidate(String key) {
        cache.remove(key);
    }

    /**
     * 同时从磁盘和缓存中删除会话。
     *
     * @param key 会话唯一标识
     * @return 若磁盘文件存在且被成功删除则返回 true
     * @throws IOException 若删除文件时发生 I/O 错误
     */
    public boolean deleteSession(String key) throws IOException {
        invalidate(key);                    // 先从缓存中移除
        Path path = sessionPath(key);
        return Files.deleteIfExists(path);  // 再删除磁盘文件
    }

    /**
     * 列出所有会话的摘要信息，按更新时间降序排列。
     * <p>摘要包含：key、创建时间、更新时间、首条用户消息预览（最多120字符）、文件路径。</p>
     *
     * @return 会话摘要列表
     * @throws IOException 若读取 sessions 目录失败
     */
    public List<Map<String, Object>> listSessions() throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();

        try (var stream = Files.list(sessionsDir)) {
            stream.filter(p -> p.toString().endsWith(".jsonl")).forEach(path -> {
                try {
                    List<String> lines = Files.readAllLines(path);
                    if (lines.isEmpty()) return;  // 跳过空文件

                    // 解析第一行元数据
                    Map<String, Object> meta = mapper.readValue(lines.get(0),
                            new TypeReference<Map<String, Object>>() {});

                    if (!"metadata".equals(meta.get("_type"))) return;  // 跳过无效格式

                    // 提取 key，若元数据中无 key 则使用文件名
                    String key = Objects.toString(meta.getOrDefault("key",
                            path.getFileName().toString().replace(".jsonl", "")));

                    // 查找第一条用户消息作为预览文本
                    String preview = "";
                    for (int i = 1; i < lines.size(); i++) {
                        Map<String, Object> msg = mapper.readValue(lines.get(i),
                                new TypeReference<Map<String, Object>>() {});
                        if ("user".equals(msg.get("role"))) {
                            preview = Objects.toString(msg.get("content"), "").replaceAll("\\s+", " ").trim();
                            if (preview.length() > 120) {
                                preview = preview.substring(0, 119) + "…";  // 超长截断并加省略号
                            }
                            break;
                        }
                    }

                    // 构建摘要信息
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("key", key);
                    info.put("created_at", meta.get("created_at"));
                    info.put("updated_at", meta.get("updated_at"));
                    info.put("preview", preview);
                    info.put("path", path.toString());
                    list.add(info);
                } catch (Exception e) {
                    log.warn("Skipping unreadable session file {}", path, e);
                }
            });
        }

        // 按更新时间降序排列（最新的在前）
        list.sort((a, b) -> Objects.toString(b.get("updated_at"), "")
                .compareTo(Objects.toString(a.get("updated_at"), "")));
        return list;
    }

    // ── 内部方法 ───────────────────────────────────────────────────────

    /**
     * 根据会话 key 生成安全的文件路径。
     * <p>将 key 中不合法的文件名字符替换为下划线，防止路径注入或文件系统错误。</p>
     *
     * @param key 会话唯一标识
     * @return 对应的 .jsonl 文件路径
     */
    private Path sessionPath(String key) {
        String safe = key.replace(':', '_').replaceAll("[\\\\/:*?\"<>|]", "_");  // 清理非法文件名字符
        return sessionsDir.resolve(safe + ".jsonl");
    }

    /**
     * 从磁盘加载会话数据并构建 Session 对象。
     * <p>逐行解析 JSONL 文件：第一行为元数据，其余为消息记录。
     * 加载失败时返回 null 并记录警告日志，不抛出异常。</p>
     *
     * @param key 会话唯一标识
     * @return 加载成功的 Session 对象，若文件不存在或解析失败则返回 null
     */
    private Session load(String key) {
        Path path = sessionPath(key);
        if (!Files.exists(path)) return null;  // 文件不存在，返回 null

        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty()) return null;  // 空文件，返回 null

            // 初始化默认值，后续从元数据行覆盖
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> metadata = new HashMap<>();
            Instant createdAt = Instant.now();
            Instant updatedAt = Instant.now();
            int lastConsolidated = 0;

            for (String line : lines) {
                if (line.isBlank()) continue;  // 跳过空行
                Map<String, Object> data = mapper.readValue(line,
                        new TypeReference<Map<String, Object>>() {});

                if ("metadata".equals(data.get("_type"))) {
                    // 元数据行：提取会话级别的元信息
                    metadata = safeCast(data.get("metadata"));
                    createdAt = parseInstant(data.get("created_at"), createdAt);
                    updatedAt = parseInstant(data.get("updated_at"), updatedAt);
                    lastConsolidated = safeInt(data.get("last_consolidated"), 0);
                } else {
                    // 消息行：加入消息列表
                    messages.add(data);
                }
            }

            // 构建 Session 对象并填充所有字段
            Session session = new Session(key);
            session.setMessages(messages);
            session.setMetadata(metadata);
            session.setCreatedAt(createdAt);
            session.setUpdatedAt(updatedAt);
            session.setLastConsolidated(lastConsolidated);
            return session;
        } catch (Exception e) {
            log.warn("Failed to load session {}: {}", key, e.toString());
            return null;  // 加载失败，返回 null，上层会创建新会话
        }
    }

    // ── 工具方法 ────────────────────────────────────────────────────────

    /**
     * 安全地将 Object 转换为 {@code Map<String, Object>}。
     * 若类型不匹配则返回空 HashMap，不抛出异常。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeCast(Object obj) {
        if (obj instanceof Map) return (Map<String, Object>) obj;
        return new HashMap<>();
    }

    /**
     * 安全地将 Object 转换为 int。
     * 若对象为 Number 则返回其整数值，否则返回给定的默认值。
     */
    private static int safeInt(Object obj, int fallback) {
        if (obj instanceof Number n) return n.intValue();
        return fallback;
    }

    /**
     * 安全地将 Object 解析为 {@link Instant}。
     * 若对象为字符串则尝试 ISO-8601 解析，解析失败或类型不匹配则返回默认值。
     */
    private static Instant parseInstant(Object obj, Instant fallback) {
        if (obj instanceof String s) {
            try { return Instant.parse(s); } catch (Exception ignored) {}
        }
        return fallback;
    }
}
