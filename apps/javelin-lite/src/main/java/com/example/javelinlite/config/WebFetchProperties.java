package com.example.javelinlite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "javelin-lite.web-fetch")
public record WebFetchProperties(
        boolean enabled,
        String userAgent,
        int defaultMaxChars,
        int maxInMemoryBytes,
        boolean ssrfGuardEnabled,
        Duration timeout
) {

    public WebFetchProperties {
        userAgent = userAgent == null || userAgent.isBlank()
                ? "JavelinLite/1.0 (+https://github.com/laishengkai20050204/JavelinAi)"
                : userAgent.strip();
        defaultMaxChars = defaultMaxChars <= 0 ? 20_000 : Math.min(defaultMaxChars, 100_000);
        maxInMemoryBytes = maxInMemoryBytes <= 0 ? 2 * 1024 * 1024 : maxInMemoryBytes;
        timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(20)
                : timeout;
    }
}
