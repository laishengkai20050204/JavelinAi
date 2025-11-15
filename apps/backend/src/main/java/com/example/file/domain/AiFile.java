package com.example.file.domain;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AiFile {

    private String id;

    private String userId;

    private String conversationId;

    private String bucket;

    private String objectKey;

    private String filename;

    private Long sizeBytes;

    private String mimeType;

    /**
     * USER_UPLOAD / PYTHON_OUTPUT / WEB_FETCH_DOWNLOAD ...
     */
    private String source;

    private String sha256;

    private LocalDateTime createdAt;

    private LocalDateTime deletedAt;
}
