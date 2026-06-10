package com.nanobot.provider;

import java.util.List;
import java.util.Map;

/**
 * Abstract contract for a large language model provider.
 *
 * <p>Mirrors {@code nanobot/providers/base.py :: LLMProvider}.</p>
 */
@FunctionalInterface
public interface LLMProvider {

    /**
     * Send a list of conversation messages to the LLM and return the response.
     *
     * @param messages ordered conversation history (role/content/tool_calls/…)
     * @return the model's text response and optional tool calls
     */
    LLMResponse chat(List<Map<String, Object>> messages);
}
