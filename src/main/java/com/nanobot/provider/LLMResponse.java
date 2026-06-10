package com.nanobot.provider;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Response from an LLM provider.
 * Mirrors {@code nanobot/providers/base.py :: LLMResponse}.
 */
public record LLMResponse(
        String content,
        List<ToolCallRequest> toolCalls,
        String finishReason,
        Map<String, Integer> usage) {

    public LLMResponse {
        toolCalls = toolCalls != null ? Collections.unmodifiableList(toolCalls) : Collections.emptyList();
        usage = usage != null ? Collections.unmodifiableMap(usage) : Collections.emptyMap();
        finishReason = finishReason != null ? finishReason : "stop";
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
