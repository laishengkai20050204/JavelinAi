package com.example.controller;

import com.example.api.dto.UploadFileResponse;
import com.example.file.service.AiFileService;
import com.example.storage.MinioProps;
import com.example.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final StorageService storageService;
    private final MinioProps minioProps;
    private final AiFileService aiFileService;   // ⭐ 新增：文件记录服务（MyBatis）

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
                // 把上传的分段内容聚合成一个 DataBuffer（这里注意大文件内存占用）
                DataBufferUtils.join(filePart.content())
                        .flatMap(buffer -> {
                            try {
                                int size = buffer.readableByteCount();
                                byte[] bytes = new byte[size];
                                buffer.read(bytes);   // 把内容复制到 byte[]

                                String filename = filePart.filename();
                                String objectKey = storageService.buildUserResourceKey(uid, cid, filename);

                                MediaType ct = filePart.headers().getContentType();
                                String contentTypeStr = (ct != null ? ct.toString() : null);

                                // 可选：算一个 sha256（需要 commons-codec 依赖）
                                String sha256 = DigestUtils.sha256Hex(bytes);

                                // 1. 确保桶存在
                                // 2. 上传字节到 MinIO
                                // 3. 生成预签名 GET 下载链接
                                return storageService.ensureBucket(bucket)
                                        .then(storageService.uploadBytes(bucket, objectKey, bytes, filename))
                                        .then(storageService.presignGet(bucket, objectKey, expiry))
                                        // 4. 成功拿到 URL 后，写 ai_file 表
                                        .flatMap(url ->
                                                Mono.fromCallable(() ->
                                                                aiFileService.saveUserUpload(
                                                                        uid,
                                                                        cid,
                                                                        bucket,
                                                                        objectKey,
                                                                        filename,
                                                                        (long) size,
                                                                        contentTypeStr,
                                                                        sha256
                                                                )
                                                        )
                                                        .subscribeOn(Schedulers.boundedElastic())
                                                        // 5. 把保存好的记录转换成返回给前端的 DTO
                                                        .map(saved -> new UploadFileResponse(
                                                                saved.getId(),       // ⭐ fileId（如果你在 DTO 里加了）
                                                                bucket,
                                                                objectKey,
                                                                url,
                                                                size,
                                                                contentTypeStr
                                                        ))
                                        );
                            } finally {
                                // DataBuffer 只在这里用一次，已经复制到 bytes 里，释放掉避免内存泄漏
                                DataBufferUtils.release(buffer);
                            }
                        })
        );
    }
}
