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
 * Edit a file by exact string search-and-replace.
 *
 * <p>This is the simplest form of editing — find one occurrence of {@code search}
 * and replace it with {@code replace}.  For multi-block changes, use write_file.</p>
 */
public class EditFileTool extends Tool {

    @Override public String name() { return "edit_file"; }

    @Override public String description() {
        return "在文件中搜索并替换指定文本（仅替换首次出现的匹配项）。"
                + " 适用于小范围修改。大范围或结构性改动请使用 write_file。";
    }

    @Override public Map<String, Object> parametersSchema() {
        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "要编辑的文件路径");

        Map<String, Object> searchProp = new LinkedHashMap<>();
        searchProp.put("type", "string");
        searchProp.put("description", "要搜索的原始文本（需精确匹配，含空格和换行）");

        Map<String, Object> replaceProp = new LinkedHashMap<>();
        replaceProp.put("type", "string");
        replaceProp.put("description", "替换后的新文本");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", pathProp);
        props.put("search", searchProp);
        props.put("replace", replaceProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("path", "search", "replace"));
        return schema;
    }

    @Override public String execute(Map<String, Object> args, ToolContext ctx) {
        String rawPath = String.valueOf(args.getOrDefault("path", ""));
        String search = String.valueOf(args.getOrDefault("search", ""));
        String replace = String.valueOf(args.getOrDefault("replace", ""));

        Path filePath = Path.of(ctx.workspace()).resolve(rawPath).normalize();
        if (!filePath.startsWith(Path.of(ctx.workspace()).normalize())) {
            return "[错误] 路径超出工作区范围: " + rawPath;
        }
        if (!Files.exists(filePath)) {
            return "[错误] 文件不存在: " + rawPath;
        }

        try {
            String original = Files.readString(filePath);
            int idx = original.indexOf(search);
            if (idx == -1) {
                return "[错误] 未找到匹配文本。请确认 search 参数与文件内容完全一致（含空格和换行）。";
            }

            String modified = original.substring(0, idx)
                    + replace
                    + original.substring(idx + search.length());
            Files.writeString(filePath, modified);

            // 提供简单的 diff 信息
            int lineNum = original.substring(0, idx).split("\n", -1).length;
            return String.format("文件已修改: %s (第 %d 行附近，%d → %d 字符)",
                    rawPath, lineNum, search.length(), replace.length());
        } catch (IOException e) {
            return "[错误] 编辑失败: " + e.getMessage();
        }
    }
}
