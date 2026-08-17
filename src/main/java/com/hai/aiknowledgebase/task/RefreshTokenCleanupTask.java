package com.hai.aiknowledgebase.task;

import com.hai.aiknowledgebase.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupTask {

    private final RefreshTokenService refreshTokenService;

    /**
     * 每天凌晨 3:00 清理已过期 7 天的 RefreshToken 记录
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanExpiredTokens() {
        log.info("开始清理过期 RefreshToken...");
        int deleted = refreshTokenService.cleanExpired();
        log.info("清理过期 RefreshToken 完成: {} 条", deleted);
    }
}