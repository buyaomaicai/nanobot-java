package com.nanobot.tool.builtin;

import com.nanobot.tool.Tool;
import com.nanobot.tool.ToolContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Execute a shell command in the workspace.
 *
 * <p>Uses {@link ProcessBuilder} to spawn a subprocess.  stdout and stderr
 * are captured and returned together.  A hard timeout prevents runaway commands.</p>
 */
public class ShellTool extends Tool {

    private static final int MAX_OUTPUT_CHARS = 10_000;
    private static final int DEFAULT_TIMEOUT_SEC = 60;

    @Override public String name() { return "exec_shell"; }

    @Override public String description() {
        return "在工作区中执行 shell 命令（Windows 使用 cmd.exe，Unix 使用 sh）。"
                + " 输出最多返回 " + (MAX_OUTPUT_CHARS / 1000) + "k 字符。"
                + " 命令默认 " + DEFAULT_TIMEOUT_SEC + " 秒超时。"
                + " 注意：这是一个真实执行环境，谨慎使用破坏性命令。";
    }

    @Override public Map<String, Object> parametersSchema() {
        Map<String, Object> cmdProp = new LinkedHashMap<>();
        cmdProp.put("type", "string");
        cmdProp.put("description", "要执行的 shell 命令");

        Map<String, Object> cwdProp = new LinkedHashMap<>();
        cwdProp.put("type", "string");
        cwdProp.put("description", "工作目录（相对于工作区），默认为工作区根目录");

        Map<String, Object> timeoutProp = new LinkedHashMap<>();
        timeoutProp.put("type", "integer");
        timeoutProp.put("description", "超时秒数，默认 " + DEFAULT_TIMEOUT_SEC + "，最大 300");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", cmdProp);
        props.put("working_dir", cwdProp);
        props.put("timeout", timeoutProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("command"));
        return schema;
    }

    @Override public String execute(Map<String, Object> args, ToolContext ctx) {
        String command = String.valueOf(args.getOrDefault("command", ""));
        String workingDir = String.valueOf(args.getOrDefault("working_dir", ""));
        int timeout = toInt(args.get("timeout"), DEFAULT_TIMEOUT_SEC);
        timeout = Math.clamp(timeout, 1, 300);

        // build ProcessBuilder
        ProcessBuilder pb;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }

        // working directory
        Path cwd = workingDir.isEmpty()
                ? Path.of(ctx.workspace())
                : Path.of(ctx.workspace()).resolve(workingDir).normalize();
        if (!cwd.startsWith(Path.of(ctx.workspace()).normalize())) {
            return "[错误] 工作目录超出工作区范围: " + workingDir;
        }
        pb.directory(cwd.toFile());

        // merge stderr into stdout for simplicity
        pb.redirectErrorStream(true);

        try {
            Process proc = pb.start();

            // read stdout asynchronously (prevents deadlock if output buffer fills)
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try {
                    proc.getInputStream().transferTo(out);
                } catch (IOException ignored) {}
            });
            reader.start();

            // wait with timeout
            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                reader.interrupt();
                return "[错误] 命令超时（" + timeout + " 秒）: " + truncate(command);
            }
            reader.join(1000); // give reader thread a moment to finish

            int exitCode = proc.exitValue();
            String output = out.toString();

            // truncate huge output
            if (output.length() > MAX_OUTPUT_CHARS) {
                output = output.substring(0, MAX_OUTPUT_CHARS)
                        + "\n…（输出已截断，共 " + output.length() + " 字符）";
            }

            return String.format("exit=%d\n%s", exitCode, output.isEmpty() ? "(无输出)" : output);
        } catch (IOException | InterruptedException e) {
            return "[错误] 命令执行失败: " + e.getMessage();
        }
    }

    private static int toInt(Object val, int fallback) {
        if (val instanceof Number n) return n.intValue();
        return fallback;
    }

    private static String truncate(String s) {
        return s.length() <= 100 ? s : s.substring(0, 100) + "…";
    }
}
