package com.hai.aiknowledgebase.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 加解密工具（AES/GCM/NoPadding）。
 *
 * <p>主密钥来自配置 {@code model.secret-key}（建议通过环境变量 {@code MODEL_CONFIG_SECRET} 注入）。
 * 未配置主密钥时 {@link #isConfigured()} 返回 false，此时只能使用环境变量引用方式保存 Key。</p>
 */
@Slf4j
@Component
public class ApiKeyCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    @Value("${model.secret-key:}")
    private String secret;

    /** 是否已配置主密钥。 */
    public boolean isConfigured() {
        return secret != null && !secret.isBlank();
    }

    /** 加密明文 Key，返回 Base64(iv + ciphertext)。 */
    public String encrypt(String plain) {
        if (!isConfigured()) {
            throw new IllegalStateException("未配置 model.secret-key，无法加密存储 API Key");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密 Base64(iv + ciphertext)，失败返回 null。 */
    public String decrypt(String cipherText) {
        if (!isConfigured() || cipherText == null || cipherText.isBlank()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("API Key 解密失败: {}", e.getMessage());
            return null;
        }
    }

    /** 由主密钥派生 256 位 AES 密钥（SHA-256）。 */
    private SecretKeySpec keySpec() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }
}
