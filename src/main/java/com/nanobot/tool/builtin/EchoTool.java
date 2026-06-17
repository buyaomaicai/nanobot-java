package com.nanobot.tool.builtin;

import com.nanobot.tool.Tool;
import com.nanobot.tool.ToolContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Echoes back the input — the simplest possible tool, useful for
 * verifying that the tool-calling pipeline works end-to-end.
 */
public class EchoTool extends Tool {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "回显你发送的文字。当你想要确认某个内容或测试工具调用时使用。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> messageProp = new LinkedHashMap<>();
        messageProp.put("type", "string");
        messageProp.put("description", "要回显的文字内容");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("message", messageProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("message"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String message = String.valueOf(arguments.getOrDefault("message", ""));
        return "[echo] " + message;
    }
}
