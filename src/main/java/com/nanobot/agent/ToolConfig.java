package com.nanobot.agent;

import com.nanobot.tool.ToolRegistry;
import com.nanobot.tool.builtin.*;
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
        // filesystem
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        // shell
        registry.register(new ShellTool());
        // web
        registry.register(new WebSearchTool());
        registry.register(new FetchUrlTool());
        // diagnostics
        registry.register(new EchoTool());
        registry.register(new TimeTool());
    }
}
