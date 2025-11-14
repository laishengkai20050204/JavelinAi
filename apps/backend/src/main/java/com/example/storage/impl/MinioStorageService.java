package com.example.storage.impl;

import com.example.storage.MinioProps;
import com.example.storage.StorageService;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.http.Method;
import io.minio.messages.Item;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

@Service
public class MinioStorageService implements StorageService {

    private final MinioClient client;
    private final MinioProps props;
    private final Tika tika = new Tika();

    public MinioStorageService(MinioClient client, MinioProps props) {
        this.client = client;
        this.props = props;
    }

    /** 获取默认桶 */
    public String getDefaultBucket() {
        return props.getDefaultBucket();
    }

    /** 构建对象键名：建议按照 {userId}/{conversationId}/{fileName} 组织 */
    public String buildObjectKey(String userId, String conversationId, String fileName) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return String.format(
                "python-outputs/%04d/%02d/%02d/%s/%s/%s",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                safe(userId), safe(conversationId), fileName
        );
    }

    /** ✅ 新增：用户上传的资源文件，固定放在 resources/ 子目录下 */
    @Override
    public String buildUserResourceKey(String userId, String conversationId, String filename) {
        // 如果你想目录叫 resource 而不是 resources，这里改成 "resource/" 即可
        return buildObjectKey(userId, conversationId, "resources/" + filename);
    }


    private String safe(String s) {
        if (s == null) return "na";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 确保桶存在（幂等操作） */
    public Mono<Void> ensureBucket(String bucket) {
        return Mono.fromRunnable(() -> {
            try {
                boolean exists = client.bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build()
                );
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
            } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException | IOException e) {
                throw new RuntimeException("Error handling bucket operation", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /** 上传本地文件路径 */
    public Mono<String> uploadFile(String bucket, String objectKey, Path file) {
        return Mono.fromCallable(() -> {
            if (!Files.exists(file)) {
                throw new FileNotFoundException(file.toString());
            }
            String contentType = detectContentType(file);
            client.uploadObject(
                    UploadObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .filename(file.toString())
                            .contentType(contentType)
                            .build()
            );
            return objectKey;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 上传字节数组 */
    public Mono<String> uploadBytes(String bucket, String objectKey, byte[] data, String filenameHint) {
        return Mono.fromCallable(() -> {
            String contentType = detectContentType(filenameHint, data);
            try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
                // objectSize 已知，用 data.length；partSize 设为 -1 让 SDK 自适应
                client.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectKey)
                                .stream(in, data.length, -1)
                                .contentType(contentType)
                                .build()
                );
            }
            return objectKey;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取对象（以 InputStream 形式返回）
     * 注意：调用方需要在消费完毕后手动 close()，否则会泄露连接。
     * 如果不想自己管理流，推荐使用 getObjectBytes 或 downloadToFile。
     */
    public Mono<InputStream> getObject(String bucket, String objectKey) {
        return Mono.fromCallable(() -> (InputStream) client.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(objectKey).build()
                ))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取对象的全部字节（方法内部会自动关闭流）
     * 适合小文件或确实需要一次性把内容读入内存的场景。
     */
    public Mono<byte[]> getObjectBytes(String bucket, String objectKey) {
        return Mono.fromCallable(() -> {
            try (GetObjectResponse in = client.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(objectKey).build()
            )) {
                // Java 9+ 方法；若你项目是 Java 8，可改成 ByteArrayOutputStream 手动读
                return in.readAllBytes();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 下载对象到本地文件（方法内部会自动关闭流）
     */
    public Mono<Path> downloadToFile(String bucket, String objectKey, Path destFile) {
        return Mono.fromCallable(() -> {
            Path parent = destFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (GetObjectResponse in = client.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(objectKey).build()
            )) {
                Files.copy(in, destFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return destFile;
        }).subscribeOn(Schedulers.boundedElastic());
    }


    /** 删除对象 */
    public Mono<Void> deleteObject(String bucket, String objectKey) {
        return Mono.fromRunnable(() -> {
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException | IOException e) {
                throw new RuntimeException("Error deleting object", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /** 预签名下载链接（自动规范化过期秒数到 1s~7d） */
    public Mono<String> presignGet(String bucket, String objectKey, Duration expiry) {
        return Mono.fromCallable(() -> {
            long seconds = expiry.getSeconds();
            if (seconds < 1) seconds = 1;
            // MinIO 限制最大 7 天（含）
            long max = 7L * 24 * 60 * 60;
            if (seconds > max) seconds = max;

            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry((int) seconds, TimeUnit.SECONDS)
                            .build()
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 列举某前缀下的对象名 */
    public Flux<String> listObjects(String bucket, String prefix) {
        return Flux.defer(() -> {
            Iterable<Result<Item>> it = client.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .recursive(true)
                            .prefix(prefix)
                            .build()
            );
            return Flux.fromIterable(it)
                    .publishOn(Schedulers.boundedElastic())
                    .map(res -> {
                        try {
                            return res.get().objectName();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ========= 工具方法 =========

    private String detectContentType(Path file) throws IOException {
        String contentType = Files.probeContentType(file);
        if (contentType != null) return contentType;
        return tika.detect(file); // 无法推测时用 Tika
    }

    private String detectContentType(String filenameHint, byte[] data) {
        String contentType = tika.detect(data, filenameHint != null ? filenameHint : "");
        return contentType != null ? contentType : "application/octet-stream";
    }

    public <T> Mono<T> withObject(String bucket, String objectKey,
                                  java.util.function.Function<InputStream, T> reader) {
        return Mono.using(
                () -> client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build()),
                (InputStream in) -> Mono.fromCallable(() -> reader.apply(in))
                        .subscribeOn(Schedulers.boundedElastic()),
                in -> { try { in.close(); } catch (IOException ignore) {} }
        );
    }

    public Mono<Boolean> exists(String bucket, String objectKey) {
        return Mono.fromCallable(() -> {
            try {
                client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
                return true;
            } catch (ErrorResponseException e) {
                if (e.errorResponse().code().equals("NoSuchKey")) return false;
                throw e;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<String> presignPut(String bucket, String objectKey, Duration expiry) {
        return Mono.fromCallable(() -> {
            long seconds = Math.min(Math.max(expiry.getSeconds(), 1), 7L*24*60*60);
            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry((int) seconds, TimeUnit.SECONDS)
                            .build()
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }


    private String safeFileName(String s) {
        if (s == null) return "unnamed";
        // 去掉路径分隔、只保留常见字符
        String base = s.replaceAll("[/\\\\]+", "_").replaceAll("[^a-zA-Z0-9._-]", "_");
        // 避免过长
        return base.length() > 120 ? base.substring(0, 120) : base;
    }
}
