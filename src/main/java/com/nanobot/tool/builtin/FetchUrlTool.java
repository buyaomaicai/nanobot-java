package com.nanobot.tool.builtin;

import com.nanobot.tool.Tool;
import com.nanobot.tool.ToolContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch the content of a URL and return it as plain text.
 */
public class FetchUrlTool extends Tool {

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int MAX_CHARS = 20_000;

    @Override public String name() { return "fetch_url"; }

    @Override public String description() {
        return "获取指定 URL 的内容（纯文本）。最多返回 " + (MAX_CHARS / 1000)
                + "k 字符。用于获取 web_search 找到的网页详情。";
    }

    @Override public Map<String, Object> parametersSchema() {
        Map<String, Object> urlProp = new LinkedHashMap<>();
        urlProp.put("type", "string");
        urlProp.put("description", "要抓取的完整 URL");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("url", urlProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("url"));
        return schema;
    }

    @Override public String execute(Map<String, Object> args, ToolContext ctx) {
        String urlStr = String.valueOf(args.getOrDefault("url", ""));

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "nanobot-java/0.2")
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            // crude HTML-to-text: strip tags
            String text = body.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                    .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS) + "\n…（已截断，共 " + text.length() + " 字符）";
            }

            return String.format("HTTP %d  %s\n\n%s", resp.statusCode(), urlStr, text);
        } catch (Exception e) {
            return "[错误] 抓取失败: " + e.getMessage();
        }
    }
}
