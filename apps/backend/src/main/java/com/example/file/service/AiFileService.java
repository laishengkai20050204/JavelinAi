package com.example.file.service;

import com.example.file.domain.AiFile;

import java.util.List;
import java.util.Optional;

public interface AiFileService {

    /**
     * 保存一条“用户上传文件”的记录
     */
    AiFile saveUserUpload(String userId,
                          String conversationId,
                          String bucket,
                          String objectKey,
                          String filename,
                          Long sizeBytes,
                          String mimeType,
                          String sha256);

    Optional<AiFile> findById(String id);

    List<AiFile> listUserConversationFiles(String userId, String conversationId);
}
