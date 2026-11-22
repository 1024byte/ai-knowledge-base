package com.hai.aiknowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * <h2>参数值读取器（检索链统一入口）</h2>
 *
 * <p>对 {@link ParamService#resolveAll(Long)} 做类型化封装，供检索链按需读取动态参数。
 * 所有 getter 均带 {@code fallback} 兜底：当参数未在元数据/配置中命中时返回 fallback，
 * 由调用方传入现有的 {@code @Value}/{@code ThresholdsConfig} 值，实现「yml 兜底」。</p>
 *
 * <p>解析结果已在 {@link ParamService} 内 30s 缓存，此处每次 getter 命中缓存后仅做 O(1) 映射读取。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParamValueProvider {

    private final ParamService paramService;

    /**
     * 获取某作用域下的完整生效参数映射。
     *
     * @param kbId 知识库 ID，null 表示全局作用域
     */
    public Map<String, String> getResolved(Long kbId) {
        return paramService.resolveAll(kbId);
    }

    // ==================== 类型化读取 ====================

    public String getString(String key, Long kbId, String fallback) {
        String v = getResolved(kbId).get(key);
        return v != null ? v : fallback;
    }

    public int getInt(String key, Long kbId, int fallback) {
        String v = getResolved(kbId).get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("参数 {} 的值 {} 无法解析为 int，回退默认 {}", key, v, fallback);
            return fallback;
        }
    }

    public double getDouble(String key, Long kbId, double fallback) {
        String v = getResolved(kbId).get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            log.warn("参数 {} 的值 {} 无法解析为 double，回退默认 {}", key, v, fallback);
            return fallback;
        }
    }

    public boolean getBoolean(String key, Long kbId, boolean fallback) {
        String v = getResolved(kbId).get(key);
        if (v == null) {
            return fallback;
        }
        return Boolean.parseBoolean(v.trim());
    }

    // ==================== 全局作用域便捷重载 ====================

    public String getString(String key, String fallback) {
        return getString(key, null, fallback);
    }

    public int getInt(String key, int fallback) {
        return getInt(key, null, fallback);
    }

    public double getDouble(String key, double fallback) {
        return getDouble(key, null, fallback);
    }

    public boolean getBoolean(String key, boolean fallback) {
        return getBoolean(key, null, fallback);
    }
}