package com.example.javelinlite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "javelin-lite.web-search")
public record WebSearchProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        int defaultTopK,
        String defaultLang,
        String defaultCountry,
        Duration timeout
) {

    public WebSearchProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        apiKey = apiKey == null ? "" : apiKey.strip();
        defaultTopK = defaultTopK <= 0 ? 5 : Math.min(defaultTopK, 10);
        defaultLang = normalizeOptional(defaultLang);
        defaultCountry = normalizeOptional(defaultCountry);
        timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(20)
                : timeout;
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank()
                ? "https://google.serper.dev"
                : value.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.strip();
    }
}
