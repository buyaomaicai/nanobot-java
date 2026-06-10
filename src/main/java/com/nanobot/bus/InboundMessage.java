package com.nanobot.bus;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Message received from a chat channel.
 *
 * Fields mirror nanobot-main/nanobot/bus/events.py InboundMessage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class InboundMessage {

    /** Channel name: telegram, discord, slack, whatsapp, etc. */
    private String channel;

    /** User identifier from the channel. */
    private String senderId;

    /** Chat / channel / thread identifier from the channel. */
    private String chatId;

    /** Message text content. */
    private String content;

    /** When the message was received. Defaults to now. */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /** Media URLs attached to the message. */
    @Builder.Default
    private List<String> media = new ArrayList<>();

    /** Channel-specific metadata (routing, trace flags, etc.). */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /** Optional override for thread-scoped sessions. */
    private String sessionKeyOverride;

    /**
     * Unique session key: override if set, otherwise {@code channel:chatId}.
     */
    public String getSessionKey() {
        if (sessionKeyOverride != null && !sessionKeyOverride.isBlank()) {
            return sessionKeyOverride;
        }
        return channel + ":" + chatId;
    }
}
