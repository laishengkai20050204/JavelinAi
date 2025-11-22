package com.example.tools.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 图像缓存管理器：避免重复从存储读取同一张图片
 */
@Slf4j
@Component
public class ImageCacheManager {

    // 缓存键结构
    public record ImageKey(String bucket, String objectKey) {}

    // 使用 Caffeine 缓存（自动过期、大小限制）
    private final Cache<ImageKey, BufferedImage> imageCache = Caffeine.newBuilder()
            .maximumSize(100)  // 最多缓存 100 张图片
            .expireAfterWrite(10, TimeUnit.MINUTES)  // 10分钟后过期
            .recordStats()  // 记录缓存统计
            .build();

    /**
     * 获取或加载图像
     */
    public BufferedImage getOrLoad(
            String bucket,
            String objectKey,
            ImageLoader loader
    ) {
        ImageKey key = new ImageKey(bucket, objectKey);

        BufferedImage image = imageCache.get(key, k -> {
            try {
                log.debug("[ImageCache] Loading image: {}/{}", bucket, objectKey);
                return loader.load();
            } catch (Exception e) {
                log.warn("[ImageCache] Failed to load image: {}/{}", bucket, objectKey, e);
                return null;
            }
        });

        // 定期输出缓存统计
        if (Math.random() < 0.1) {  // 10% 概率打印
            log.debug("[ImageCache] Stats: {}", imageCache.stats());
        }

        return image;
    }

    /**
     * 清除特定图像缓存
     */
    public void evict(String bucket, String objectKey) {
        imageCache.invalidate(new ImageKey(bucket, objectKey));
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        imageCache.invalidateAll();
    }

    @FunctionalInterface
    public interface ImageLoader {
        BufferedImage load() throws Exception;
    }
}