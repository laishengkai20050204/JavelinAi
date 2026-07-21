package com.example.javelinlite;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import com.example.javelinlite.config.WebFetchProperties;
import com.example.javelinlite.tools.WebFetchTool;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetchToolTest {

    @Test
    @SuppressWarnings("unchecked")
    void extractsReadablePageContent() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        ClientResponse.create(HttpStatus.OK)
                                .header("Content-Type", MediaType.TEXT_HTML_VALUE)
                                .body("""
                                        <html>
                                          <head><title>Example page</title></head>
                                          <body>
                                            <nav>Navigation</nav>
                                            <article><h1>Hello</h1><p>Readable page content.</p></article>
                                            <script>ignored()</script>
                                          </body>
                                        </html>
                                        """)
                                .build()
                ));

        WebFetchTool tool = new WebFetchTool(
                builder,
                properties(false)
        );
        ToolCall call = new ToolCall(
                "call-fetch-1",
                "web_fetch",
                Map.of("url", "https://example.com/article")
        );

        ToolResult result = tool.execute(call, request()).block();

        assertNotNull(result);
        assertTrue(result.successful());
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertTrue(String.valueOf(data.get("title")).contains("Example page"));
        assertTrue(String.valueOf(data.get("excerpt")).contains("Readable page content."));
        assertFalse(String.valueOf(data.get("excerpt")).contains("Navigation"));
        assertFalse(String.valueOf(data.get("excerpt")).contains("ignored"));
    }

    @Test
    void blocksLoopbackTargetsBeforeSendingTheRequest() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.error(
                        new AssertionError("HTTP request must not be sent")
                ));
        WebFetchTool tool = new WebFetchTool(builder, properties(true));
        ToolCall call = new ToolCall(
                "call-fetch-2",
                "web_fetch",
                Map.of("url", "http://127.0.0.1/private")
        );

        ToolResult result = tool.execute(call, request()).block();

        assertNotNull(result);
        assertFalse(result.successful());
        assertTrue(result.error().contains("private")
                || result.error().contains("loopback")
                || result.error().contains("reserved"));
    }

    private static WebFetchProperties properties(boolean ssrfGuardEnabled) {
        return new WebFetchProperties(
                true,
                "JavelinLiteTest/1.0",
                5_000,
                256 * 1024,
                ssrfGuardEnabled,
                Duration.ofSeconds(1)
        );
    }

    private static ChatRequest request() {
        return new ChatRequest(
                null,
                "user-1",
                "person-1:user-1",
                "fetch a page",
                "auto",
                "",
                Map.of("personId", "person-1")
        );
    }
}
