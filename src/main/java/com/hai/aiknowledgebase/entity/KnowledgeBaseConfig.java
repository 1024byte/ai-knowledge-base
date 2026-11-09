package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <h2>知识库高级配置实体</h2>
 *
 * <p>与 {@link KnowledgeBase} 一对一关联，存储检索和切片策略等高级参数。</p>
 *
 * <h3>字段说明</h3>
 * <table>
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>kbId</td><td>BIGINT(PK+FK)</td><td>关联 knowledge_base.id，级联删除</td></tr>
 *   <tr><td>splitStrategy</td><td>VARCHAR(50)</td><td>切片策略：RECURSIVE / SENTENCE / PARAGRAPH / LINE</td></tr>
 *   <tr><td>embeddingModel</td><td>VARCHAR(100)</td><td>向量模型名称</td></tr>
 *   <tr><td>retrievalTopK</td><td>INT</td><td>检索返回数量上限</td></tr>
 *   <tr><td>similarityThreshold</td><td>FLOAT</td><td>向量检索最低相似度阈值（0.0~1.0）</td></tr>
 * </table>
 */
@Data
@TableName("knowledge_base_config")
public class KnowledgeBaseConfig {

    @TableId
    private Long kbId;

    private String splitStrategy;

    private String embeddingModel;

    private Integer retrievalTopK;

    private Double similarityThreshold;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}