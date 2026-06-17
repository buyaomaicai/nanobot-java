package com.nanobot.provider;

import java.util.List;
import java.util.Map;

/**
 * Mock LLM provider for development — echoes back a canned response
 * so the full message pipeline can be tested without an API key.
 *
 * <p>No longer a Spring bean.  To use it, manually instantiate or wire it.
 * The default provider is now {@link DeepSeekProvider}.</p>
 */
public class SimpleLLMProvider implements LLMProvider {

    @Override
    public LLMResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        // extract the last user message for context-aware echo
        String lastUserContent = "(empty)";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                lastUserContent = String.valueOf(messages.get(i).get("content"));
                break;
            }
        }

        String reply = "[mock] 收到你的消息：「" + lastUserContent + "」—— 这是模拟回复。";

        // simulate a brief "thinking" delay
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        return new LLMResponse(reply, List.of(), "stop", Map.of("mock_tokens", reply.length()));
    }
}
