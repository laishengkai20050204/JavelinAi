package com.example.javelinlite.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(
        name = "javelin-lite.model.enabled",
        havingValue = "true"
)
public final class OpenAiCompatibleChatGateway implements ChatGateway {

    private final WebClient webClient;
    private final ModelProperties properties;

    public OpenAiCompatibleChatGateway(
            WebClient.Builder webClientBuilder,
            ModelProperties properties
    ) {
        this.properties = properties;

        WebClient.Builder configuredBuilder = webClientBuilder.clone()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        if (StringUtils.hasText(properties.apiKey())) {
            configuredBuilder.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + properties.apiKey()
            );
        }

        this.webClient = configuredBuilder.build();
    }

    @Override
    public Mono<JsonNode> call(Map<String, Object> payload) {
        Map<String, Object> requestBody = new LinkedHashMap<>(payload);
        String activeModel = stringValue(requestBody.get("model"));
        if (!StringUtils.hasText(activeModel)) {
            activeModel = properties.model();
        }
        if (!StringUtils.hasText(activeModel)) {
            return Mono.error(new IllegalStateException(
                    "No model configured. Set JAVELIN_LITE_MODEL or ChatRequest.model"
            ));
        }

        requestBody.put("model", activeModel);
        requestBody.put("stream", false);

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new IllegalStateException(
                                        "Model provider returned HTTP "
                                                + response.statusCode().value()
                                                + ": "
                                                + abbreviate(body, 1200)
                                ))
                )
                .bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Model provider returned an empty response"
                )))
                .timeout(properties.timeout());
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String abbreviate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maximumLength) + "...";
    }
}
