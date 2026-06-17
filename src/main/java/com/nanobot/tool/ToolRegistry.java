package com.nanobot.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for agent tools — dynamic registration, lookup, and
 * LLM-compatible definition export.
 *
 * <p>Mirrors {@code nanobot/agent/tools/registry.py :: ToolRegistry}.</p>
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private volatile List<Map<String, Object>> cachedDefinitions;

    // ── registration ──────────────────────────────────────────────────

    /** Register a tool. Overwrites any existing tool with the same name. */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        cachedDefinitions = null; // invalidate cache
        log.debug("Registered tool: {}", tool.name());
    }

    /** Remove a tool by name. */
    public void unregister(String name) {
        tools.remove(name);
        cachedDefinitions = null;
        log.debug("Unregistered tool: {}", name);
    }

    // ── lookup ────────────────────────────────────────────────────────

    /** Get a tool by name, or {@code null}. */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** Check whether a tool is registered. */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    // ── definitions (LLM-facing) ──────────────────────────────────────

    /**
     * Return all registered tool definitions in stable name order.
     * The result is cached until the next register/unregister call.
     */
    public List<Map<String, Object>> getDefinitions() {
        List<Map<String, Object>> defs = cachedDefinitions;
        if (defs != null) {
            return defs;
        }
        // compute with stable ordering for prompt-cache friendliness
        defs = tools.values().stream()
                .sorted(Comparator.comparing(Tool::name))
                .map(Tool::getDefinition)
                .toList();
        cachedDefinitions = defs;
        return defs;
    }
}
