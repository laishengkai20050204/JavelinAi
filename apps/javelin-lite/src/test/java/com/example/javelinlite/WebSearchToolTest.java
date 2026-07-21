package com.example.javelinlite;

import com.example.javelinlite.api.ChatRequest;
import com.example.javelinlite.api.ToolCall;
import com.example.javelinlite.api.ToolResult;
import com.example.javelinlite.config.WebSearchProperties;
import com.example.javelinlite.tools.WebSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {

    @Test
    @SuppressWarnings("unchecked")
    void normalizesSerperWebResults() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        ClientResponse.create(HttpStatus.OK)
                                .header(
                                        "Content-Type",
                                        MediaType.APPLICATION_JSON_VALUE
                                )
                                .body("""
                                        {
                                          "organic": [{
                                            "title": "Example result",
                                            "link": "https://example.com/article",
                                            "snippet": "A concise result snippet."
                                          }]
                                        }
                                        """)
                                .build()
                ));

        WebSearchProperties properties = new WebSearchProperties(
                true,
                "https://google.serper.dev",
                "test-key",
                5,
                "en",
                "us",
                Duration.ofSeconds(1)
        );
        WebSearchTool tool = new WebSearchTool(builder, properties);

        ToolCall call = new ToolCall(
                "call-search-1",
                "web_search",
                Map.of("q", "example query", "top_k", 3)
        );

        ToolResult result = tool.execute(call, request()).block();

        assertNotNull(result);
        assertTrue(result.successful());
        Map<String, Object> data = (Map<String, Object>) result.data();
        List<Map<String, Object>> results =
                (List<Map<String, Object>>) data.get("results");
        assertEquals(1, results.size());
        assertEquals("Example result", results.get(0).get("title"));
        assertEquals("https://example.com/article", results.get(0).get("url"));
        assertTrue(String.valueOf(data.get("text")).contains("A concise result snippet."));
    }

    private static ChatRequest request() {
        return new ChatRequest(
                null,
                "user-1",
                "person-1:user-1",
                "search the web",
                "auto",
                "",
                Map.of("personId", "person-1")
        );
    }
}
