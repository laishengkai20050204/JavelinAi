package com.example.api.dto;

/**
 * 用户上传文件之后返回给前端 / LLM 的信息
 */
public record UploadFileResponse(
        String fileId,      // ⭐ 数据库里的 ai_file.id
        String bucket,      // 存到哪个桶
        String objectKey,   // MinIO 里的对象 key
        String url,         // 预签名下载链接（给 LLM / 前端用）
        long size,          // 文件大小（字节）
        String contentType  // 媒体类型，比如 image/png
) {}
