package com.hai.aiknowledgebase.model;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * API Key 解析器：优先按环境变量引用取值，其次解密库内密文。
 *
 * <p>支持 {@code env:XXX} 前缀，也兼容直接写变量名（如 {@code DEEPSEEK_API_KEY}）。
 * 两者都取不到时返回 null，由上层回落到 yml bootstrap 配置。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecretResolver {

    private static final String ENV_PREFIX = "env:";

    private final ApiKeyCipher apiKeyCipher;

    /**
     * 解析 API Key。
     *
     * @param apiKeyRef    环境变量名（可带 env: 前缀）
     * @param apiKeyCipher 加密密文
     * @return 明文 Key；均未配置时返回 null
     */
    public String resolve(String apiKeyRef, String apiKeyCipher) {
        if (apiKeyRef != null && !apiKeyRef.isBlank()) {
            String name = apiKeyRef.startsWith(ENV_PREFIX)
                    ? apiKeyRef.substring(ENV_PREFIX.length())
                    : apiKeyRef;
            String value = System.getenv(name.trim());
            if (value == null || value.isBlank()) {
                value = System.getProperty(name.trim());
            }
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
            log.debug("环境变量 {} 未设置，尝试使用密文配置", name);
        }
        if (apiKeyCipher != null && !apiKeyCipher.isBlank()) {
            return apiKeyCipher(apiKeyCipher);
        }
        return null;
    }

    private String apiKeyCipher(String cipherText) {
        String plain = apiKeyCipher.decrypt(cipherText);
        if (plain == null) {
            log.warn("模型配置中的 API Key 密文无法解密，请检查 model.secret-key 是否与加密时一致");
        }
        return plain;
    }

    /** 加密明文 Key（供保存配置时使用）。 */
    public String encrypt(String plain) {
        return apiKeyCipher.encrypt(plain);
    }

    /** 是否具备加密能力（已配置主密钥）。 */
    public boolean canEncrypt() {
        return apiKeyCipher.isConfigured();
    }
}
