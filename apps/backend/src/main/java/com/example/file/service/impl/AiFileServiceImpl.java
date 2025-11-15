package com.example.file.service.impl;

import com.example.file.domain.AiFile;
import com.example.file.service.AiFileService;
import com.example.mapper.AiFileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiFileServiceImpl implements AiFileService {

    private final AiFileMapper aiFileMapper;

    @Override
    public AiFile saveUserUpload(String userId,
                                 String conversationId,
                                 String bucket,
                                 String objectKey,
                                 String filename,
                                 Long sizeBytes,
                                 String mimeType,
                                 String sha256) {

        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        AiFile file = AiFile.builder()
                .id(id)
                .userId(userId)
                .conversationId(conversationId)
                .bucket(bucket)
                .objectKey(objectKey)
                .filename(filename)
                .sizeBytes(sizeBytes)
                .mimeType(mimeType)
                .source("USER_UPLOAD")
                .sha256(sha256)
                .createdAt(now)
                .deletedAt(null)
                .build();

        aiFileMapper.insert(file);
        return file;
    }

    @Override
    public Optional<AiFile> findById(String id) {
        return Optional.ofNullable(aiFileMapper.selectById(id));
    }

    @Override
    public List<AiFile> listUserConversationFiles(String userId, String conversationId) {
        return aiFileMapper.listByConversation(userId, conversationId);
    }
}
