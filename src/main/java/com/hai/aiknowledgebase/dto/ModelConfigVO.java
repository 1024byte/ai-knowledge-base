package com.hai.aiknowledgebase.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 模型配置展示对象（不包含任何明文或密文 Key）。
 */
@Data
@Builder
public class ModelConfigVO {

    private Long id;
    private String bizType;
    private String name;
    private String provider;
    private String baseUrl;
    private String modelName;

    /** 是否已配置 Key（不返回 Key 本身） */
    private Boolean hasApiKey;

    /** Key 配置方式：NONE（未配置）/ ENV（环境变量引用）/ ENCRYPTED（加密存储） */
    private String apiKeyMode;

    /** Key 环境变量名，仅 apiKeyMode=ENV 时有值；用于编辑表单回填 */
    private String apiKeyRef;

    private Double temperature;
    private Integer maxTokens;
    private Integer timeoutMs;
    private Map<String, Object> extraJson;

    /** 是否生效 */
    private Boolean active;

    private Integer sortOrder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
