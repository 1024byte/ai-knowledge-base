package com.hai.aiknowledgebase.dto;

import lombok.Data;

import java.util.Map;

/**
 * 模型配置保存请求。
 *
 * <p>{@code apiKey} 为明文，仅用于写入（服务端加密存储），不会回显；
 * 也可改用 {@code apiKeyRef} 指定环境变量名。两者都不传表示保持原 Key。</p>
 */
@Data
public class ModelConfigRequest {

    /** CHAT / REWRITE / RERANK */
    private String bizType;

    private String name;

    /** OPENAI_COMPAT / OLLAMA / ONNX / DASHSCOPE / AUTO */
    private String provider;

    private String baseUrl;

    private String modelName;

    /** 明文 API Key（写入时加密存储） */
    private String apiKey;

    /** 环境变量名（与 apiKey 二选一） */
    private String apiKeyRef;

    /** 是否清空已保存的 Key */
    private Boolean clearApiKey;

    private Double temperature;

    private Integer maxTokens;

    private Integer timeoutMs;

    private Map<String, Object> extraJson;

    /** 创建后是否立即激活 */
    private Boolean active;

    private Integer sortOrder;
}
