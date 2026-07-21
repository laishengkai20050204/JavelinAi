package com.example.javelinlite.tools;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import com.example.javelinlite.config.WebFetchProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@ConditionalOnProperty(
        prefix = "javelin-lite.web-fetch",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public final class WebFetchTool implements AiTool {

    private final WebClient webClient;
    private final WebFetchProperties properties;

    public WebFetchTool(
            WebClient.Builder webClientBuilder,
            WebFetchProperties properties
    ) {
        this.properties = properties;
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs()
                        .maxInMemorySize(properties.maxInMemoryBytes()))
                .build();
        this.webClient = webClientBuilder.clone()
                .exchangeStrategies(strategies)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .build();
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch a public HTTP(S) page and extract readable text. Use after web_search when the page content is needed.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("url", Map.of(
                "type", "string",
                "description", "Absolute public HTTP(S) URL",
                "minLength", 1
        ));
        fields.put("max_chars", Map.of(
                "type", "integer",
                "minimum", 200,
                "maximum", 100_000,
                "default", properties.defaultMaxChars()
        ));
        fields.put("selector", Map.of(
                "type", "string",
                "description", "Optional CSS selector for the content to extract"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", fields);
        schema.put("required", List.of("url"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Mono<ToolResult> execute(ToolCall call, ChatRequest request) {
        String rawUrl = text(call.arguments().get("url"));
        if (!StringUtils.hasText(rawUrl)) {
            return Mono.just(ToolResult.failure(call, "Missing required parameter: url"));
        }

        int maxChars = clamp(
                integer(call.arguments().get("max_chars"), properties.defaultMaxChars()),
                200,
                100_000
        );
        String selector = text(call.arguments().get("selector")).strip();

        return Mono.fromCallable(() -> validateTarget(rawUrl, maxChars, selector))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::fetch)
                .map(page -> ToolResult.success(call, toResult(page)))
                .onErrorResume(error -> Mono.just(ToolResult.failure(
                        call,
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage()
                )));
    }

    private Mono<FetchedPage> fetch(Target target) {
        return webClient.get()
                .uri(target.uri())
                .accept(
                        MediaType.TEXT_HTML,
                        MediaType.APPLICATION_XHTML_XML,
                        MediaType.TEXT_PLAIN,
                        MediaType.APPLICATION_JSON,
                        MediaType.APPLICATION_XML
                )
                .exchangeToMono(response -> {
                    HttpStatusCode status = response.statusCode();
                    MediaType contentType = response.headers().contentType().orElse(null);

                    if (status.isError()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new IllegalStateException(
                                        "web_fetch returned HTTP "
                                                + status.value()
                                                + ": "
                                                + abbreviate(body, 800)
                                )));
                    }

                    if (!isTextual(contentType)) {
                        return Mono.error(new IllegalStateException(
                                "web_fetch only supports textual content, received: "
                                        + (contentType == null ? "unknown" : contentType)
                        ));
                    }

                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> extract(target, body, contentType));
                })
                .timeout(properties.timeout());
    }

    private FetchedPage extract(Target target, String source, MediaType contentType) {
        Document document = Jsoup.parse(source, target.uri().toString());
        document.select("script, style, noscript, svg, canvas, form, footer, nav, aside").remove();

        String extractedText;
        if (StringUtils.hasText(target.selector())) {
            Elements selected = document.select(target.selector());
            if (selected.isEmpty()) {
                throw new IllegalArgumentException(
                        "CSS selector matched no elements: " + target.selector()
                );
            }
            extractedText = selected.text();
        } else {
            Elements preferred = document.select(
                    "article, main, #content, .content, #main, .post, .article"
            );
            extractedText = preferred.text();
            if (!StringUtils.hasText(extractedText) && document.body() != null) {
                extractedText = document.body().text();
            }
        }

        String title = compact(document.title());
        String excerpt = truncate(compact(extractedText), target.maxChars());
        return new FetchedPage(target.uri(), title, excerpt, contentType);
    }

    private Target validateTarget(
            String rawUrl,
            int maxChars,
            String selector
    ) throws Exception {
        URI uri = normalize(rawUrl);
        String scheme = uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);

        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URLs containing user information are not allowed");
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new IllegalArgumentException("URL must contain a valid host");
        }
        if (properties.ssrfGuardEnabled()) {
            ensurePublicHost(uri.getHost());
        }

        return new Target(uri, maxChars, selector);
    }

    private static URI normalize(String rawUrl) throws URISyntaxException {
        URI source = new URI(rawUrl.strip());
        return new URI(
                source.getScheme(),
                source.getUserInfo(),
                source.getHost(),
                source.getPort(),
                source.getPath(),
                source.getQuery(),
                null
        );
    }

    private static void ensurePublicHost(String host) throws Exception {
        if ("localhost".equalsIgnoreCase(host) || host.endsWith(".localhost")) {
            throw new IllegalArgumentException("Target host is not public");
        }

        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IllegalArgumentException("Target host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException(
                        "Target host resolves to a private, loopback, link-local, or reserved address"
                );
            }
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 0 || first == 127) {
                return true;
            }
            return first == 100 && second >= 64 && second <= 127;
        }

        return bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc);
    }

    private static boolean isTextual(MediaType contentType) {
        if (contentType == null) {
            return true;
        }
        if ("text".equalsIgnoreCase(contentType.getType())) {
            return true;
        }
        String subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
        return subtype.contains("json")
                || subtype.contains("xml")
                || subtype.contains("xhtml")
                || subtype.contains("javascript");
    }

    private static Map<String, Object> toResult(FetchedPage page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", page.uri().toString());
        result.put("title", page.title());
        result.put("excerpt", page.excerpt());
        result.put("contentType", page.contentType() == null
                ? "unknown"
                : page.contentType().toString());
        result.put("fetchedAt", OffsetDateTime.now().toString());
        result.put("text", readableText(page));
        return result;
    }

    private static String readableText(FetchedPage page) {
        StringBuilder text = new StringBuilder("Fetched web page");
        if (StringUtils.hasText(page.title())) {
            text.append(" \"").append(page.title()).append("\"");
        }
        text.append(" (").append(page.uri()).append(")");
        if (StringUtils.hasText(page.excerpt())) {
            text.append(": ").append(page.excerpt());
        }
        return text.toString();
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maximumLength);
    }

    private static String abbreviate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maximumLength) + "...";
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

    private record Target(URI uri, int maxChars, String selector) {
    }

    private record FetchedPage(
            URI uri,
            String title,
            String excerpt,
            MediaType contentType
    ) {
    }
}
