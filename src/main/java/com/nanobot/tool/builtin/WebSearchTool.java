package com.nanobot.tool.builtin;

import com.nanobot.tool.Tool;
import com.nanobot.tool.ToolContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search the web using DuckDuckGo HTML (no API key required).
 */
public class WebSearchTool extends Tool {

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // crude extraction: match <a class="result-link"... href="URL">Title</a>
    private static final Pattern RESULT_PAT = Pattern.compile(
            "<a[^>]*class=\"[^\"]*result-link[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE);

    @Override public String name() { return "web_search"; }

    @Override public String description() {
        return "使用 DuckDuckGo 搜索网页。返回最多 5 条结果的标题和 URL。"
                + " 需要获取具体网页内容时，使用 fetch_url 工具。";
    }

    @Override public Map<String, Object> parametersSchema() {
        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "搜索关键词");

        Map<String, Object> maxProp = new LinkedHashMap<>();
        maxProp.put("type", "integer");
        maxProp.put("description", "最大结果数，默认 5，最大 10");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", queryProp);
        props.put("max_results", maxProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override public String execute(Map<String, Object> args, ToolContext ctx) {
        String query = String.valueOf(args.getOrDefault("query", ""));
        int maxResults = Math.clamp(toInt(args.get("max_results"), 5), 1, 10);

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encoded;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "nanobot-java/0.2")
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            StringBuilder sb = new StringBuilder("搜索: ").append(query).append("\n\n");
            Matcher m = RESULT_PAT.matcher(body);
            int count = 0;
            while (m.find() && count < maxResults) {
                String href = m.group(1).replace("&amp;", "&");
                String title = m.group(2).replaceAll("<[^>]+>", "").trim();
                if (!title.isEmpty()) {
                    sb.append(++count).append(". ").append(title).append("\n");
                    sb.append("   ").append(href).append("\n\n");
                }
            }
            if (count == 0) {
                sb.append("(未找到结果，DuckDuckGo 可能返回了验证页面)");
            }
            return sb.toString();
        } catch (Exception e) {
            return "[错误] 搜索失败: " + e.getMessage();
        }
    }

    private static int toInt(Object val, int fallback) {
        if (val instanceof Number n) return n.intValue();
        return fallback;
    }
}
