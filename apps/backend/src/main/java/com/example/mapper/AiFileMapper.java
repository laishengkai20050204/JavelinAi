package com.example.mapper;

import com.example.file.domain.AiFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiFileMapper {

    int insert(AiFile file);

    AiFile selectById(@Param("id") String id);

    List<AiFile> listByConversation(
            @Param("userId") String userId,
            @Param("conversationId") String conversationId
    );
}
