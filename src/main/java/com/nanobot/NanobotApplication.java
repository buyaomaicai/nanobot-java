package com.nanobot;

import com.nanobot.agent.AgentLoop;
import com.nanobot.bus.InboundMessage;
import com.nanobot.bus.MessageBus;
import com.nanobot.bus.OutboundMessage;
import com.nanobot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Scanner;

/**
 * Nanobot CLI entry point — wires three threads:
 * <ol>
 *   <li><b>Input</b>  — reads stdin → publishes {@link InboundMessage}</li>
 *   <li><b>Agent</b>  — consumes inbound → calls LLM → publishes {@link OutboundMessage}</li>
 *   <li><b>Output</b> — consumes outbound → prints to stdout</li>
 * </ol>
 */
@SpringBootApplication
public class NanobotApplication {

    private static final Logger log = LoggerFactory.getLogger(NanobotApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(NanobotApplication.class, args);
    }

    @Bean
    CommandLineRunner cliRunner(MessageBus bus,
                                SessionManager sessionManager,
                                AgentLoop agentLoop) {
        return args -> {

            log.info("Nanobot CLI started.  Type a message, or /exit to quit.");

            // ── thread 1: input ───────────────────────────────────
            Thread inputThread = new Thread(() -> {
                try (Scanner scanner = new Scanner(System.in)) {
                    while (true) {
                        System.out.print("> ");
                        String line = scanner.nextLine();
                        if (line == null) break;          // EOF
                        if ("/exit".equalsIgnoreCase(line.trim())) {
                            bus.publishInbound(InboundMessage.builder()
                                    .channel("cli")
                                    .senderId("user")
                                    .chatId("default")
                                    .content("/exit")
                                    .build());
                            break;
                        }
                        bus.publishInbound(InboundMessage.builder()
                                .channel("cli")
                                .senderId("user")
                                .chatId("default")
                                .content(line)
                                .build());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                log.info("Input thread exiting.");
            }, "nanobot-input");

            // ── thread 2: agent ──────────────────────────────────
            Thread agentThread = new Thread(() -> {
                try {
                    while (true) {
                        InboundMessage in = bus.consumeInbound();
                        if ("/exit".equals(in.getContent())) {
                            bus.publishOutbound(OutboundMessage.builder()
                                    .channel("cli")
                                    .chatId("default")
                                    .content("/exit")
                                    .build());
                            break;
                        }
                        agentLoop.processOne(in);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // persist sessions on exit
                try { sessionManager.flushAll(); } catch (Exception e) { log.warn("flush failed", e); }
                log.info("Agent thread exiting.");
            }, "nanobot-agent");

            // ── thread 3: output ─────────────────────────────────
            Thread outputThread = new Thread(() -> {
                try {
                    while (true) {
                        OutboundMessage out = bus.consumeOutbound();
                        if ("/exit".equals(out.getContent())) break;
                        System.out.println("\n🤖 " + out.getContent());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                log.info("Output thread exiting.");
            }, "nanobot-output");

            inputThread.setDaemon(true);
            agentThread.setDaemon(true);
            outputThread.setDaemon(true);

            inputThread.start();
            agentThread.start();
            outputThread.start();

            // wait for all to finish
            inputThread.join();
            agentThread.join();
            outputThread.join();

            log.info("Nanobot CLI shut down.");
        };
    }
}
