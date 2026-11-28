package com.hai.aiknowledgebase.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 模型配置的运行时不可变快照。
 *
 * <p>由 {@code ModelConfigService#toProfile} 从数据库配置构建，{@code apiKey} 为解析后的明文，
 * 仅存在于内存，不落日志、不出接口。</p>
 */
@Getter
@Builder
public class ModelProfile {

    private final Long id;
    private final ModelBizType bizType;
    private final String name;
    private final ModelProviderType provider;
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;
    private final Double temperature;
    private final Integer maxTokens;
    private final Integer timeoutMs;
    private final Map<String, Object> extra;

    /** 是否已配置 API Key。 */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 客户端缓存指纹：配置一致则复用客户端，任一影响连接的字段变化都会重建。
     * 不包含明文 Key，仅使用其哈希。
     */
    public String fingerprint() {
        return provider + "|" + bizType + "|" + nz(baseUrl) + "|" + nz(modelName)
                + "|" + temperature + "|" + maxTokens + "|" + timeoutMs
                + "|" + (apiKey == null ? "0" : Integer.toHexString(apiKey.hashCode()));
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
