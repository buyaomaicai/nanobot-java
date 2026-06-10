package com.nanobot.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Async message bus that decouples chat channels from the agent core.
 *
 * <p>Channels push messages to the inbound queue; the agent loop consumes
 * them and pushes responses to the outbound queue.  Two independent
 * {@link BlockingQueue}s keep producers and consumers isolated.</p>
 *
 * <p>Mirrors {@code nanobot/bus/queue.py :: MessageBus}.</p>
 */
@Component
public class MessageBus {

    private static final Logger log = LoggerFactory.getLogger(MessageBus.class);

    private final BlockingQueue<InboundMessage> inbound;
    private final BlockingQueue<OutboundMessage> outbound;

    public MessageBus() {
        this.inbound = new LinkedBlockingQueue<>();
        this.outbound = new LinkedBlockingQueue<>();
    }

    // ── inbound ────────────────────────────────────────────────────────

    /**
     * Publish a message from a channel to the agent.
     * Blocks if the queue is at capacity.
     */
    public void publishInbound(InboundMessage msg) throws InterruptedException {
        log.debug("publish inbound  channel={}  sender={}  content={}",
                msg.getChannel(), msg.getSenderId(), truncate(msg.getContent()));
        inbound.put(msg);
    }

    /**
     * Consume the next inbound message, blocking until one is available.
     */
    public InboundMessage consumeInbound() throws InterruptedException {
        InboundMessage msg = inbound.take();
        log.debug("consume inbound  channel={}  sender={}",
                msg.getChannel(), msg.getSenderId());
        return msg;
    }

    /**
     * Consume the next inbound message, waiting at most the given time.
     *
     * @return the message, or {@code null} if the timeout elapses
     */
    public InboundMessage consumeInbound(long timeout, TimeUnit unit)
            throws InterruptedException {
        return inbound.poll(timeout, unit);
    }

    // ── outbound ───────────────────────────────────────────────────────

    /**
     * Publish a response from the agent to channels.
     * Blocks if the queue is at capacity.
     */
    public void publishOutbound(OutboundMessage msg) throws InterruptedException {
        log.debug("publish outbound  channel={}  chat={}  content={}",
                msg.getChannel(), msg.getChatId(), truncate(msg.getContent()));
        outbound.put(msg);
    }

    /**
     * Consume the next outbound message, blocking until one is available.
     */
    public OutboundMessage consumeOutbound() throws InterruptedException {
        OutboundMessage msg = outbound.take();
        log.debug("consume outbound  channel={}  chat={}",
                msg.getChannel(), msg.getChatId());
        return msg;
    }

    /**
     * Consume the next outbound message, waiting at most the given time.
     *
     * @return the message, or {@code null} if the timeout elapses
     */
    public OutboundMessage consumeOutbound(long timeout, TimeUnit unit)
            throws InterruptedException {
        return outbound.poll(timeout, unit);
    }

    // ── sizing ─────────────────────────────────────────────────────────

    /** Number of pending inbound messages. */
    public int getInboundSize() {
        return inbound.size();
    }

    /** Number of pending outbound messages. */
    public int getOutboundSize() {
        return outbound.size();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
