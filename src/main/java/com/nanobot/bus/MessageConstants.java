package com.nanobot.bus;

/**
 * Message bus protocol constants.
 *
 * Mirrors nanobot-main/nanobot/bus/events.py constant definitions.
 */
public final class MessageConstants {

    private MessageConstants() {
        // utility class
    }

    /**
     * OutboundMessage.metadata key for structured, channel-agnostic UI payloads.
     * Value is JSON-serializable with at least "kind"; rich clients may render it
     * and other channels may ignore unknown keys.
     */
    public static final String OUTBOUND_META_AGENT_UI = "_agent_ui";

    /**
     * Internal-only inbound metadata used by in-process channels to ask the agent
     * loop to update runtime state without going through a user session.
     */
    public static final String INBOUND_META_RUNTIME_CONTROL = "_runtime_control";

    /** Runtime control ack marker. */
    public static final String RUNTIME_CONTROL_ACK = "_ack";

    /** Runtime control: MCP reload directive. */
    public static final String RUNTIME_CONTROL_MCP_RELOAD = "mcp_reload";

    /**
     * OutboundMessage.metadata key — when {@code true}, the message is a
     * streaming delta and should be printed without a trailing newline.
     */
    public static final String OUTBOUND_META_STREAM_PROGRESS = "_stream_progress";
}
