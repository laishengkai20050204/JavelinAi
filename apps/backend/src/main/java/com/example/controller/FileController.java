package com.example.controller;

import com.example.api.dto.UploadFileResponse;
import com.example.storage.MinioProps;
import com.example.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final StorageService storageService;
    private final MinioProps minioProps;

    /**
     * 上传单个文件到 MinIO
     *
     * 表单字段：
     * - file: 文件本体（必填）
     * - userId: 用户ID（可选，默认 anonymous）
     * - conversationId: 会话ID（可选，默认 default）
     */
    @Operation(summary = "上传文件到对象存储（MinIO）")
    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<UploadFileResponse> upload(
            @RequestPart("file") Mono<FilePart> filePartMono,
            @RequestPart(value = "userId", required = false) String userId,
            @RequestPart(value = "conversationId", required = false) String conversationId
    ) {
        final String uid = (userId != null && !userId.isBlank()) ? userId : "anonymous";
        final String cid = (conversationId != null && !conversationId.isBlank()) ? conversationId : "default";

        final String bucket = storageService.getDefaultBucket();
        final Duration expiry = Duration.ofSeconds(minioProps.getPresignExpirySeconds());

        return filePartMono.flatMap(filePart ->
                DataBufferUtils.join(filePart.content())
                        .flatMap(buffer -> {
                            try {
                                int size = buffer.readableByteCount();
                                byte[] bytes = new byte[size];
                                buffer.read(bytes);

                                String filename = filePart.filename();
                                String objectKey = storageService.buildObjectKey(uid, cid, filename);

                                MediaType ct = filePart.headers().getContentType();
                                String contentTypeStr = (ct != null ? ct.toString() : null);

                                // 1. 确保桶存在
                                // 2. 上传字节
                                // 3. 生成预签名 GET 下载链接
                                return storageService.ensureBucket(bucket)
                                        .then(storageService.uploadBytes(bucket, objectKey, bytes, filename))
                                        .then(storageService.presignGet(bucket, objectKey, expiry))
                                        .map(url -> new UploadFileResponse(
                                                bucket,
                                                objectKey,
                                                url,
                                                size,
                                                contentTypeStr
                                        ));
                            } finally {
                                // 释放 DataBuffer，避免内存泄漏
                                DataBufferUtils.release(buffer);
                            }
                        })
        );
    }
}
