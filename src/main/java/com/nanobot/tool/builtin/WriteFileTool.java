package com.nanobot.tool.builtin;

import com.nanobot.tool.Tool;
import com.nanobot.tool.ToolContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Write content to a file in the workspace. Creates parent directories.
 */
public class WriteFileTool extends Tool {

    @Override public String name() { return "write_file"; }

    @Override public String description() {
        return "将内容写入工作区中的文件。如果文件不存在则创建，父目录会自动创建。"
                + " 路径必须是相对于工作区根目录的相对路径。";
    }

    @Override public Map<String, Object> parametersSchema() {
        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "文件相对于工作区的路径");

        Map<String, Object> contentProp = new LinkedHashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description", "要写入文件的完整内容");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", pathProp);
        props.put("content", contentProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("path", "content"));
        return schema;
    }

    @Override public String execute(Map<String, Object> args, ToolContext ctx) {
        String rawPath = String.valueOf(args.getOrDefault("path", ""));
        String content = String.valueOf(args.getOrDefault("content", ""));

        Path filePath = Path.of(ctx.workspace()).resolve(rawPath).normalize();
        if (!filePath.startsWith(Path.of(ctx.workspace()).normalize())) {
            return "[错误] 路径超出工作区范围: " + rawPath;
        }

        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            long size = Files.size(filePath);
            long lines = Files.readAllLines(filePath).size();
            return String.format("文件已写入: %s (%d 行, %d 字节)", rawPath, lines, size);
        } catch (IOException e) {
            return "[错误] 写入失败: " + e.getMessage();
        }
    }
}
