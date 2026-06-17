package com.nanobot.tool;

/**
 * Runtime context passed to tools during execution.
 *
 * <p>Mirrors {@code nanobot/agent/tools/context.py :: ToolContext}.</p>
 */
public record ToolContext(
        /** Workspace root directory (e.g. {@code ~/.nanobot}). */
        String workspace) {

    public static final ToolContext DEFAULT = new ToolContext(
            System.getProperty("user.home") + "/.nanobot");
}
