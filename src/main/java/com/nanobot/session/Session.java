package com.nanobot.session;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A conversation session — the unit of multi-turn dialogue.
 *
 * <p>Mirrors {@code nanobot/session/manager.py :: Session}.</p>
 */
@Data
public class Session {

    /** Unique session key, typically {@code channel:chatId}. */
    private final String key;

    /** Ordered list of messages. Each message is a map with keys like
     * {@code role}, {@code content}, {@code timestamp}, {@code tool_calls}, etc. */
    private List<Map<String, Object>> messages = new ArrayList<>();

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    /** Arbitrary session-level metadata (title, tags, etc.). */
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * Number of messages already consolidated to external storage.
     * When {@code getHistory} is called, only messages from this index onward are returned.
     */
    private int lastConsolidated = 0;

    public Session(String key) {
        this.key = key;
    }

    // ── message helpers ────────────────────────────────────────────────

    /**
     * Add a message to this session. {@code kwargs} are merged directly into
     * the message map (e.g. {@code media}, {@code tool_calls}).
     */
    public void addMessage(String role, String content, Map<String, Object> kwargs) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        msg.put("timestamp", Instant.now().toString());
        if (kwargs != null) {
            msg.putAll(kwargs);
        }
        messages.add(msg);
        updatedAt = Instant.now();
    }

    /** Convenience overload without extra kwargs. */
    public void addMessage(String role, String content) {
        addMessage(role, content, null);
    }

    // ── history ────────────────────────────────────────────────────────

    /**
     * Return the slice of messages since the last consolidation that should
     * be sent to the LLM as conversation history.
     *
     * @param maxMessages maximum number of recent messages to return (0 = unlimited)
     */
    public List<Map<String, Object>> getHistory(int maxMessages) {
        //获取最近的上下文信息，从上一个长期记忆的存档点开始，但是截断之后需要处理开头，确保是用户的信息开头跳过tool或者assist开头
        List<Map<String, Object>> unconsolidated = messages.subList(lastConsolidated, messages.size());

        int limit = maxMessages > 0 ? Math.min(maxMessages, unconsolidated.size()) : unconsolidated.size();
        int from = unconsolidated.size() - limit;

        // prefer starting at a user turn
        for (int i = from; i < unconsolidated.size(); i++) {
            if ("user".equals(unconsolidated.get(i).get("role"))) {
                from = i;
                break;
            }
        }

        // skip orphan tool results at the front
        while (from < unconsolidated.size()) {
            String role = (String) unconsolidated.get(from).get("role");
            if (!"tool".equals(role)) break;
            from++;
        }

        return new ArrayList<>(unconsolidated.subList(from, unconsolidated.size()));
    }

    // ── lifecycle ──────────────────────────────────────────────────────

    /** Clear all messages and reset to initial state. */
    public void clear() {
        messages = new ArrayList<>();
        lastConsolidated = 0;
        updatedAt = Instant.now();
        metadata.remove("_last_summary");
    }

    /** Mark all current messages as consolidated (for memory compaction). */
    public void markConsolidated() {
        lastConsolidated = messages.size();
    }
}
