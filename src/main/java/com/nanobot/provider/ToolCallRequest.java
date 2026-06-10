package com.nanobot.provider;

import java.util.Collections;
import java.util.Map;

/**
 * A tool call request from the LLM.
 * Mirrors {@code nanobot/providers/base.py :: ToolCallRequest}.
 */
public record ToolCallRequest(
        String id,
        String name,
        Map<String, Object> arguments) {

    public ToolCallRequest {
        arguments = arguments != null ? Collections.unmodifiableMap(arguments) : Collections.emptyMap();
    }
}
