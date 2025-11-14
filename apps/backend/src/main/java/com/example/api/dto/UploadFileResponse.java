package com.example.api.dto;

public record UploadFileResponse(
        String bucket,       // 存到哪个桶
        String objectKey,    // MinIO 里的对象 key
        String url,          // 预签名下载链接（给 LLM / 前端用）
        long size,           // 文件大小（字节）
        String contentType   // 媒体类型，比如 image/png
) {}
