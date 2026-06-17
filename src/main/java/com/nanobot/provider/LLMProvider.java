package com.nanobot.provider;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Abstract contract for a large language model provider.
 *
 * <p>Mirrors {@code nanobot/providers/base.py :: LLMProvider}.</p>
 */
public interface LLMProvider {

    /**
     * Send messages to the LLM. Convenience overload without tool definitions.
     */
    default LLMResponse chat(List<Map<String, Object>> messages) {
        return chat(messages, Collections.emptyList());
    }

    /**
     * Send messages and tool definitions to the LLM.
     *
     * @param messages  ordered conversation history (role/content/tool_calls/…)
     * @param tools     OpenAI-format tool definitions (empty if tools are unavailable)
     * @return the model's text response and optional tool calls
     */
    LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools);
}
