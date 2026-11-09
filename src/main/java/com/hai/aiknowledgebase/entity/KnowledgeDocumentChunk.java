package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <h2>文档切片实体</h2>
 *
 * <p>对应数据库表 {@code knowledge_document_chunk}，存储前端审校后的最终切片内容。
 * 向量化时直接从此表读取，不再重新调用切片引擎。</p>
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li>切片预览（GET /{id}/chunks）→ 前端展示自动切片结果</li>
 *   <li>前端审校 → 用户增删改切片</li>
 *   <li>保存切片（PUT /{id}/chunks）→ 全量覆盖写入本表</li>
 *   <li>向量化 → 从本表读取 → embed → PGVector + BM25</li>
 * </ol>
 *
 * <h3>级联删除</h3>
 * <p>当 {@code knowledge_document} 被删除时，对应切片自动级联删除（ON DELETE CASCADE）。</p>
 *
 * @see KnowledgeDocument 关联的文档实体
 */
@Data
@TableName("knowledge_document_chunk")
public class KnowledgeDocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 knowledge_document.id，级联删除 */
    private Long documentId;

    /** 切片序号（从 0 开始），写入时按数组顺序赋值 */
    private Integer chunkIndex;

    /** 切片文本（含上下文前缀，如 "**上下文**: 第一章\n\n..."） */
    private String text;

    /** Token 估算值 */
    private Integer tokenCount;

    /** 层级路径（如 "第一章 > 1.1 概述"） */
    private String contextPrefix;

    /** 内容类型：text / code / table */
    private String contentType;

    /** 父级段落全文 */
    private String parentText;

    /** 父级段落起始 chunk 索引 */
    private Integer parentStart;

    /** 父级段落结束 chunk 索引 */
    private Integer parentEnd;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}