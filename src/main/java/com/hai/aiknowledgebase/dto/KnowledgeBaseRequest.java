package com.hai.aiknowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * <h2>知识库创建/更新请求 DTO</h2>
 *
 * <p>合并知识库基本信息与高级配置参数，一次请求完成两张表的写入。</p>
 *
 * <h3>必填字段</h3>
 * <ul>
 *   <li>{@code name}：知识库名称</li>
 *   <li>{@code chunkLength}：切片长度</li>
 *   <li>{@code overlapLength}：重叠长度</li>
 * </ul>
 */
@Data
public class KnowledgeBaseRequest {

    @NotBlank(message = "知识库名称不能为空")
    private String name;

    private String description;

    @NotNull(message = "切片长度不能为空")
    private Integer chunkLength;

    @NotNull(message = "重叠长度不能为空")
    private Integer overlapLength;

    /** 切片策略，默认 RECURSIVE */
    private String splitStrategy;

    /** 向量模型，默认 text-embedding-3-small */
    private String embeddingModel;

    /** 检索返回数量，默认 5 */
    private Integer retrievalTopK;

    /** 相似度阈值（0.0~1.0），默认 0.7 */
    private Double similarityThreshold;
}