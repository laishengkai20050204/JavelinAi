package com.example.javelinlite.tools;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import com.example.javelinlite.config.WebSearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(
        prefix = "javelin-lite.web-search",
        name = "enabled",
        havingValue = "true"
)
public final class WebSearchTool implements AiTool {

    private final WebClient webClient;
    private final WebSearchProperties properties;

    public WebSearchTool(
            WebClient.Builder webClientBuilder,
            WebSearchProperties properties
    ) {
        this.properties = properties;
        this.webClient = webClientBuilder.clone()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-API-KEY", properties.apiKey())
                .build();
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search current web, news, or image results. Use this for fresh public information.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("q", Map.of(
                "type", "string",
                "description", "Search query",
                "minLength", 1
        ));
        fields.put("type", Map.of(
                "type", "string",
                "enum", List.of("web", "news", "images"),
                "default", "web"
        ));
        fields.put("top_k", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 10,
                "default", properties.defaultTopK()
        ));
        fields.put("lang", Map.of(
                "type", "string",
                "description", "Language preference, for example zh-cn or en"
        ));
        fields.put("country", Map.of(
                "type", "string",
                "description", "Country or region code, for example cn, sg, or us"
        ));
        fields.put("page", Map.of(
                "type", "integer",
                "minimum", 1,
                "default", 1
        ));
        fields.put("site", Map.of(
                "type", "string",
                "description", "Optional domain limiter, for example github.com"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", fields);
        schema.put("required", List.of("q"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Mono<ToolResult> execute(ToolCall call, ChatRequest request) {
        return Mono.defer(() -> {
            if (!StringUtils.hasText(properties.apiKey())) {
                return Mono.just(ToolResult.failure(
                        call,
                        "web_search is enabled but SERPER_API_KEY is missing"
                ));
            }

            String rawQuery = text(call.arguments().get("q"));
            if (!StringUtils.hasText(rawQuery)) {
                return Mono.just(ToolResult.failure(call, "Missing required parameter: q"));
            }

            String type = normalizeType(text(call.arguments().get("type")));
            int topK = clamp(integer(call.arguments().get("top_k"), properties.defaultTopK()), 1, 10);
            int page = Math.max(1, integer(call.arguments().get("page"), 1));
            String lang = valueOrDefault(text(call.arguments().get("lang")), properties.defaultLang());
            String country = valueOrDefault(text(call.arguments().get("country")), properties.defaultCountry());
            String site = normalizeSite(text(call.arguments().get("site")));
            String query = StringUtils.hasText(site)
                    ? "site:" + site + " " + rawQuery.strip()
                    : rawQuery.strip();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", query);
            body.put("num", topK);
            if (StringUtils.hasText(lang)) {
                body.put("hl", lang);
            }
            if (StringUtils.hasText(country)) {
                body.put("gl", country);
            }
            if (page > 1) {
                body.put("page", page);
            }

            String endpoint = switch (type) {
                case "news" -> "/news";
                case "images" -> "/images";
                default -> "/search";
            };

            return webClient.post()
                    .uri(endpoint)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(
                            HttpStatusCode::isError,
                            response -> response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(responseBody -> new IllegalStateException(
                                            "Serper returned HTTP "
                                                    + response.statusCode().value()
                                                    + ": "
                                                    + abbreviate(responseBody, 800)
                                    ))
                    )
                    .bodyToMono(JsonNode.class)
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                            "Serper returned an empty response"
                    )))
                    .timeout(properties.timeout())
                    .map(json -> ToolResult.success(
                            call,
                            buildResult(rawQuery.strip(), query, type, json, topK)
                    ));
        }).onErrorResume(error -> Mono.just(ToolResult.failure(
                call,
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()
        )));
    }

    private Map<String, Object> buildResult(
            String rawQuery,
            String executedQuery,
            String type,
            JsonNode json,
            int topK
    ) {
        List<Map<String, Object>> results = switch (type) {
            case "news" -> parseNews(json.path("news"), topK);
            case "images" -> parseImages(json.path("images"), topK);
            default -> parseWeb(json.path("organic"), topK);
        };

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", "serper");
        data.put("type", type);
        data.put("query", rawQuery);
        data.put("executedQuery", executedQuery);
        data.put("results", results);
        data.put("text", readableText(rawQuery, type, results));
        return data;
    }

    private static List<Map<String, Object>> parseWeb(JsonNode items, int limit) {
        if (!items.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode item : items) {
            String title = item.path("title").asText("");
            String url = item.path("link").asText("");
            if (!StringUtils.hasText(title) || !StringUtils.hasText(url)) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("title", title);
            result.put("url", url);
            result.put("snippet", firstText(item, "snippet", "description"));
            results.add(result);
            if (results.size() >= limit) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private static List<Map<String, Object>> parseNews(JsonNode items, int limit) {
        if (!items.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode item : items) {
            String title = item.path("title").asText("");
            String url = item.path("link").asText("");
            if (!StringUtils.hasText(title) || !StringUtils.hasText(url)) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("title", title);
            result.put("url", url);
            result.put("snippet", firstText(item, "snippet", "description"));
            result.put("publishedAt", item.path("date").asText(""));
            result.put("source", item.path("source").asText(""));
            results.add(result);
            if (results.size() >= limit) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private static List<Map<String, Object>> parseImages(JsonNode items, int limit) {
        if (!items.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode item : items) {
            String imageUrl = firstText(item, "imageUrl", "image");
            if (!StringUtils.hasText(imageUrl)) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("title", item.path("title").asText(""));
            result.put("url", item.path("link").asText(""));
            result.put("imageUrl", imageUrl);
            result.put("thumbnail", firstText(item, "thumbnailUrl", "thumbnail"));
            results.add(result);
            if (results.size() >= limit) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private static String readableText(
            String query,
            String type,
            List<Map<String, Object>> results
    ) {
        if (results.isEmpty()) {
            return "web_search (" + type + ") found no results for \"" + query + "\".";
        }

        StringBuilder text = new StringBuilder();
        text.append("web_search (")
                .append(type)
                .append(") results for \"")
                .append(query)
                .append("\":\n\n");

        int index = 1;
        for (Map<String, Object> result : results) {
            text.append(index++).append(". ")
                    .append(valueOrDefault(text(result.get("title")), "(no title)"))
                    .append('\n');
            appendLine(text, "URL", text(result.get("url")));
            appendLine(text, "Snippet", text(result.get("snippet")));
            appendLine(text, "Published", text(result.get("publishedAt")));
            appendLine(text, "Image", text(result.get("imageUrl")));
            text.append('\n');
        }
        return text.toString().strip();
    }

    private static void appendLine(StringBuilder target, String label, String value) {
        if (StringUtils.hasText(value)) {
            target.append("   ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeType(String value) {
        if (!StringUtils.hasText(value)) {
            return "web";
        }
        return switch (value.strip().toLowerCase()) {
            case "news" -> "news";
            case "images" -> "images";
            default -> "web";
        };
    }

    private static String normalizeSite(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.strip()
                .toLowerCase()
                .replaceFirst("^https?://", "")
                .replaceFirst("/.*$", "");
    }

    private static String valueOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String abbreviate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maximumLength) + "...";
    }
}
