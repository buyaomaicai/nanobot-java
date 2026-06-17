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
 * Read a file from the workspace.
 *
 * <p>Only relative paths are allowed — the resolved path must stay within
 * the workspace directory.  An optional line range limits output size.</p>
 */
public class ReadFileTool extends Tool {

    @Override public String name() { return "read_file"; }

    @Override public String description() {
        return "读取工作区中指定文件的内容。可以指定起始行和最大行数来分段阅读大文件。"
                + " 路径必须是相对于工作区根目录的相对路径。";
    }

    @Override public Map<String, Object> parametersSchema() {
        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "文件相对于工作区的路径，例如 pom.xml 或 src/main/java/...");

        Map<String, Object> startLineProp = new LinkedHashMap<>();
        startLineProp.put("type", "integer");
        startLineProp.put("description", "起始行号（1-based），默认为 1");

        Map<String, Object> maxLinesProp = new LinkedHashMap<>();
        maxLinesProp.put("type", "integer");
        maxLinesProp.put("description", "最多返回多少行，默认 200，最大 500");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", pathProp);
        props.put("start_line", startLineProp);
        props.put("max_lines", maxLinesProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("path"));
        return schema;
    }

    @Override public String execute(Map<String, Object> args, ToolContext ctx) {
        String rawPath = String.valueOf(args.getOrDefault("path", ""));
        int startLine = toInt(args.get("start_line"), 1);
        int maxLines = toInt(args.get("max_lines"), 200);

        // ① 路径安全：只允许相对路径，解析到 workspace 内
        Path filePath = Path.of(ctx.workspace()).resolve(rawPath).normalize();
        if (!filePath.startsWith(Path.of(ctx.workspace()).normalize())) {
            return "[错误] 路径超出工作区范围: " + rawPath;
        }
        if (!Files.exists(filePath)) {
            return "[错误] 文件不存在: " + rawPath;
        }
        if (Files.isDirectory(filePath)) {
            return "[错误] 路径是目录而非文件: " + rawPath;
        }

        // ② 读文件并截取行范围
        try {
            List<String> allLines = Files.readAllLines(filePath);
            int from = Math.max(0, startLine - 1);
            int to = Math.min(allLines.size(), from + Math.min(maxLines, 500));
            if (from >= allLines.size()) {
                return "[提示] 起始行 " + startLine + " 超出文件总行数 " + allLines.size();
            }

            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                sb.append(String.format("%6d│", i + 1)).append(allLines.get(i)).append("\n");
            }
            if (to < allLines.size()) {
                sb.append("…（共 ").append(allLines.size()).append(" 行，已截断）");
            }
            return sb.toString();
        } catch (IOException e) {
            return "[错误] 读取失败: " + e.getMessage();
        }
    }

    private static int toInt(Object val, int fallback) {
        if (val instanceof Number n) return n.intValue();
        return fallback;
    }
}
