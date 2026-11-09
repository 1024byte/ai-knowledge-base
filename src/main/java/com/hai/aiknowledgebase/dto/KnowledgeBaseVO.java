package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h2>知识库详情响应 DTO</h2>
 *
 * <p>合并 {@code knowledge_base} 与 {@code knowledge_base_config} 两张表的数据，
 * 前端一次请求即可获取完整的知识库信息。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseVO {

    // ==================== 基本信息（knowledge_base 表） ====================

    private Long id;
    private String name;
    private String description;
    private Integer chunkLength;
    private Integer overlapLength;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ==================== 高级配置（knowledge_base_config 表） ====================

    private String splitStrategy;
    private String embeddingModel;
    private Integer retrievalTopK;
    private Double similarityThreshold;
}