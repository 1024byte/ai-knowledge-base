package com.hai.aiknowledgebase.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public Cache<String, String> documentHashCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)               // 最多缓存 1 万个哈希值
                .expireAfterWrite(1, TimeUnit.HOURS) // 写入后 1 小时过期
                .recordStats()                     // 可选，记录命中统计
                .build();
    }
}
