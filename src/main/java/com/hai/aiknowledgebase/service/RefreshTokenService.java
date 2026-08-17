package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hai.aiknowledgebase.entity.RefreshToken;
import com.hai.aiknowledgebase.mapper.RefreshTokenMapper;
import com.hai.aiknowledgebase.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenMapper refreshTokenMapper;

    public RefreshToken saveOrUpdate(Long userId, String deviceId, String rawRefreshToken,
                                     int version, String deviceName, String loginIp) {
        String tokenHash = HashUtil.sha256(rawRefreshToken);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(7);

        RefreshToken existing = findByUserIdAndDeviceId(userId, deviceId);
        if (existing != null) {
            existing.setTokenHash(tokenHash);
            existing.setVersion(version);
            existing.setExpiresAt(expiresAt);
            existing.setDeviceName(deviceName);
            existing.setLoginIp(loginIp);
            existing.setLastRefreshTime(LocalDateTime.now());
            refreshTokenMapper.updateById(existing);
            log.info("设备重新登录: userId={}, deviceId={}", userId, deviceId);
            return existing;
        }

        RefreshToken entity = new RefreshToken();
        entity.setUserId(userId);
        entity.setDeviceId(deviceId);
        entity.setTokenHash(tokenHash);
        entity.setVersion(version);
        entity.setExpiresAt(expiresAt);
        entity.setDeviceName(deviceName);
        entity.setLoginIp(loginIp);
        entity.setLastRefreshTime(LocalDateTime.now());
        refreshTokenMapper.insert(entity);
        log.info("新设备登录: userId={}, deviceId={}", userId, deviceId);
        return entity;
    }

    public RefreshToken findByUserIdAndDeviceId(Long userId, String deviceId) {
        return refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getUserId, userId)
                .eq(RefreshToken::getDeviceId, deviceId));
    }

    public RefreshToken findByUserIdAndDeviceIdForRefresh(Long userId, String deviceId) {
        return refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getUserId, userId)
                .eq(RefreshToken::getDeviceId, deviceId)
                .gt(RefreshToken::getExpiresAt, LocalDateTime.now()));
    }

    @Transactional
    public void rotateTokenHash(Long userId, String deviceId, String newRawRefreshToken) {
        String newHash = HashUtil.sha256(newRawRefreshToken);
        RefreshToken entity = findByUserIdAndDeviceId(userId, deviceId);
        if (entity != null) {
            entity.setTokenHash(newHash);
            entity.setExpiresAt(LocalDateTime.now().plusDays(7));
            entity.setLastRefreshTime(LocalDateTime.now());
            refreshTokenMapper.updateById(entity);
        }
    }

    public void revokeDevice(Long userId, String deviceId) {
        refreshTokenMapper.incrementVersion(userId, deviceId);
        log.info("单端登出: userId={}, deviceId={}", userId, deviceId);
    }

    public void revokeAllDevices(Long userId) {
        refreshTokenMapper.incrementVersionAllDevices(userId);
        log.info("全局登出: userId={}", userId);
    }

    public int cleanExpired() {
        int deleted = refreshTokenMapper.delete(new LambdaQueryWrapper<RefreshToken>()
                .lt(RefreshToken::getExpiresAt, LocalDateTime.now().minusDays(7)));
        if (deleted > 0) {
            log.info("清理过期 RefreshToken: {} 条", deleted);
        }
        return deleted;
    }
}