package com.example.storage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;

public interface StorageService {

    /** 默认桶名（有的实现可以直接写死配置） */
    String getDefaultBucket();

    /** 根据业务规则构建对象 key */
    String buildPythonOutputKey(String userId, String conversationId, String fileName);

    /** ✅ 新增：专门给“用户上传资源”用 */
    String buildUserResourceKey(String userId, String conversationId, String filename);

    /** 确保桶存在（幂等） */
    Mono<Void> ensureBucket(String bucket);

    /** 上传本地文件路径 */
    Mono<String> uploadFile(String bucket, String objectKey, Path file);

    /** 上传字节数组 */
    Mono<String> uploadBytes(String bucket, String objectKey, byte[] data, String filenameHint);

    /**
     * 获取对象 InputStream
     * 注意：调用方需要在消费完后 close()
     */
    Mono<InputStream> getObject(String bucket, String objectKey);

    /**
     * 获取对象全部字节
     */
    Mono<byte[]> getObjectBytes(String bucket, String objectKey);

    /**
     * 下载到本地文件
     */
    Mono<Path> downloadToFile(String bucket, String objectKey, Path destFile);

    /** 删除对象 */
    Mono<Void> deleteObject(String bucket, String objectKey);

    /** 预签名 GET 下载链接 */
    Mono<String> presignGet(String bucket, String objectKey, Duration expiry);

    /** 按前缀列举对象名 */
    Flux<String> listObjects(String bucket, String prefix);

    /**
     * 包装一个“读对象流”的处理函数，内部帮你管理 close()
     */
    <T> Mono<T> withObject(String bucket,
                           String objectKey,
                           Function<InputStream, T> reader);

    /** 判断对象是否存在 */
    Mono<Boolean> exists(String bucket, String objectKey);

    /** 预签名 PUT 上传链接 */
    Mono<String> presignPut(String bucket, String objectKey, Duration expiry);
}
