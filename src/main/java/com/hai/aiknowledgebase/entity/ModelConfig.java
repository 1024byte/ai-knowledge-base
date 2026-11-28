package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hai.aiknowledgebase.mapper.handler.JsonbHandlers;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * <h2>模型配置实体</h2>
 *
 * <p>对应 {@code model_config} 表，统一管理 Chat / 查询改写 / Rerank 三类模型。
 * 每个 {@code bizType} 最多一条 {@code isActive=true} 的配置生效。</p>
 *
 * <p>API Key 不明文存储：{@code apiKeyRef} 为环境变量名，{@code apiKeyCipher} 为 AES-GCM 密文，
 * 二者二选一，运行时由 {@code SecretResolver} 解析。</p>
 */
@Data
@TableName("model_config")
public class ModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型：CHAT / REWRITE / RERANK */
    private String bizType;

    private String name;

    /** 供应商：OPENAI_COMPAT / OLLAMA / ONNX / DASHSCOPE / AUTO */
    private String provider;

    private String baseUrl;

    private String modelName;

    /** API Key 环境变量名（推荐），支持 env: 前缀 */
    private String apiKeyRef;

    /** AES-GCM 加密后的 API Key，与 apiKeyRef 二选一 */
    private String apiKeyCipher;

    private Double temperature;

    private Integer maxTokens;

    private Integer timeoutMs;

    @TableField(value = "extra_json", typeHandler = JsonbHandlers.ObjectMap.class)
    private Map<String, Object> extraJson;

    private Boolean isActive;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
