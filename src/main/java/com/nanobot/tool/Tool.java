package com.nanobot.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent capability — read files, run commands, search the web, etc.
 *
 * <p>Every tool exposes an OpenAI-compatible function definition
 * and an {@link #execute} method.  Subclasses only need to implement
 * the four abstract members; {@link #getDefinition()} is derived automatically.</p>
 *
 * <p>Mirrors {@code nanobot/agent/tools/base.py :: Tool}.</p>
 */
public abstract class Tool {

    /** Unique tool name used in LLM function calls (e.g. {@code "read_file"}). */
    public abstract String name();

    /** Human-readable description shown to the LLM. */
    public abstract String description();

    /**
     * JSON Schema describing the tool's parameters.
     * Example:
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "message": { "type": "string", "description": "要回显的文字" }
     *   },
     *   "required": ["message"]
     * }
     * }</pre>
     */
    public abstract Map<String, Object> parametersSchema();

    /**
     * Execute the tool with the given arguments.
     *
     * @param arguments  resolved parameter values from the LLM
     * @param ctx        runtime context (workspace, session, etc.)
     * @return tool result string sent back to the LLM
     */
    public abstract String execute(Map<String, Object> arguments, ToolContext ctx);

    /**
     * OpenAI-compatible tool definition.
     * Cached by {@link ToolRegistry} — stable ordering matters for prefix-cache reuse.
     */
    public final Map<String, Object> getDefinition() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name());
        fn.put("description", description());
        fn.put("parameters", parametersSchema());

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "function");
        def.put("function", fn);
        return def;
    }
}
