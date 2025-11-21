package com.example.storage;

import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("default")
class MinioStorageServiceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageServiceIntegrationTest.class);

    private final StorageService storageService;
    private final MinioClient client;

    @Value("${storage.minio.defaultBucket}")
    private String defaultBucket;

    private String uploadedKey;

    @Autowired
    MinioStorageServiceIntegrationTest(StorageService storageService, MinioClient client) {
        this.storageService = storageService;
        this.client = client;
    }

    @BeforeEach
    void ensureMinioReady() {
        try {
            // 任意调用确保能够连通 MinIO
            client.listBuckets();
            storageService.ensureBucket(defaultBucket).block(Duration.ofSeconds(10));
        } catch (Exception ex) {
            assumeTrue(false, "无法连接 MinIO: " + ex.getMessage());
        }
    }

    @AfterEach
    void cleanup() {
        if (uploadedKey == null) return;
        try {
            storageService.deleteObject(defaultBucket, uploadedKey)
                    .onErrorResume(err -> {
                        log.warn("删除 MinIO 对象 {} 失败: {}", uploadedKey, err.getMessage());
                        return Mono.empty();
                    })
                    .block(Duration.ofSeconds(10));
        } catch (Exception ex) {
            log.warn("清理对象 {} 时出现异常", uploadedKey, ex);
        }
    }

    @Test
    void uploadFileHitsRealMinio(@TempDir Path tempDir) throws Exception {
        Path localFile = tempDir.resolve("minio-real-upload.txt");
        Files.writeString(localFile, "真实 MinIO 上传测试 @" + Instant.now());

        uploadedKey = storageService.buildPythonOutputKey("integration", "upload", localFile.getFileName().toString());
        log.info("上传真实文件到 MinIO：bucket='{}', key='{}'", defaultBucket, uploadedKey);

        StepVerifier.create(storageService.uploadFile(defaultBucket, uploadedKey, localFile))
                .expectNext(uploadedKey)
                .verifyComplete();

        StepVerifier.create(storageService.exists(defaultBucket, uploadedKey))
                .expectNext(true)
                .verifyComplete();
    }
}
