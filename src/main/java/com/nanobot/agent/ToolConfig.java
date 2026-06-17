package com.nanobot.agent;

import com.nanobot.tool.ToolRegistry;
import com.nanobot.tool.builtin.EchoTool;
import com.nanobot.tool.builtin.TimeTool;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Register built-in tools on startup.
 */
@Configuration
public class ToolConfig {

    private final ToolRegistry registry;

    public ToolConfig(ToolRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void registerBuiltins() {
        registry.register(new EchoTool());
        registry.register(new TimeTool());
    }
}
