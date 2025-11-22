// src/main/java/com/example/storage/MinioProps.java
package com.example.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Data
@Configuration
@Primary
@ConfigurationProperties(prefix = "storage.minio")
public class MinioProps {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String region = "us-east-1";
    private boolean secure = false;
    private String defaultBucket = "javelin-dev";
    private int presignExpirySeconds = 3600;

    /** 对外暴露的 HTTP 基址，例如 <a href="https://javelinai.cloud:65019/minio">...</a> */
    private String publicBaseUrl;

    // getters/setters ...
}
