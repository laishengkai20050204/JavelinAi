// src/main/java/com/example/storage/MinioConfig.java
package com.example.storage;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MinioProps.class)
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MinioProps props) {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }
}
