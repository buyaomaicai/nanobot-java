package com.nanobot.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads {@code llm-config.json} from the classpath.
 *
 * <p>Why a separate class instead of {@code @ConfigurationProperties}?
 * Because this is a tiny flat JSON file, not a Spring-managed config tree.
 * Jackson reads it in one shot — no setters, no prefix, no ceremony.</p>
 */
public record LlmConfig(
        String apiKey,
        String baseUrl,
        String model,
        int maxTokens,
        double temperature) {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);
    private static final String RESOURCE_PATH = "llm-config.json";

    /**
     * Load config from {@code src/main/resources/llm-config.json}.
     *
     * @throws IllegalStateException if the file is missing or malformed
     */
    public static LlmConfig load() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = LlmConfig.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException(
                        "llm-config.json not found in classpath.  " +
                        "Copy src/main/resources/llm-config.json from the template and fill in your API key.");
            }
            return mapper.readValue(in, LlmConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse llm-config.json: " + e.getMessage(), e);
        }
    }
}
