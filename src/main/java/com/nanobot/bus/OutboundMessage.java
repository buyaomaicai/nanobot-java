package com.nanobot.bus;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Message to send to a chat channel.
 *
 * Fields mirror nanobot-main/nanobot/bus/events.py OutboundMessage.
 * {@code metadata} can carry routing ({@code message_id}, …), trace flags
 * ({@code _progress}), and optional {@code _agent_ui} blobs for rich clients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OutboundMessage {

    /** Target channel name. */
    private String channel;

    /** Target chat / channel / thread identifier. */
    private String chatId;

    /** Message text content. */
    private String content;

    /** Optional message id to reply to. */
    private String replyTo;

    /** Media URLs to attach. */
    @Builder.Default
    private List<String> media = new ArrayList<>();

    /**
     * Channel-specific metadata.  May include routing keys,
     * progress flags, and structured UI payloads under
     * {@link MessageConstants#OUTBOUND_META_AGENT_UI}.
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /** Keyboard buttons as rows of labels. */
    @Builder.Default
    private List<List<String>> buttons = new ArrayList<>();
}
