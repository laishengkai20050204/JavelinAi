package com.example.javelinlite.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "javelin-lite.model")
public record ModelProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Duration timeout
) {

    public ModelProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null ? "" : model.strip();
        temperature = temperature == null ? 0.8d : temperature;
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;

        if (temperature < 0.0d || temperature > 2.0d) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank()
                ? "https://openrouter.ai/api/v1"
                : value.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
