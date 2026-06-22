package com.nanobot.provider;

/**
 * Callback interface for streaming LLM responses.
 *
 * <p>The provider calls {@link #onToken} for each text chunk as it arrives,
 * and {@link #onComplete} once the full response is assembled.  If the model
 * returns tool calls they are collected into the final {@link LLMResponse}.</p>
 */
public interface StreamCallback {

    /** A new text fragment arrived.  Called zero or more times. */
    void onToken(String text);

    /** The streaming response is complete.  {@code response} is the assembled result. */
    void onComplete(LLMResponse response);

    /** An unrecoverable error occurred during streaming. */
    default void onError(Throwable error) {
        // no-op by default
    }
}
