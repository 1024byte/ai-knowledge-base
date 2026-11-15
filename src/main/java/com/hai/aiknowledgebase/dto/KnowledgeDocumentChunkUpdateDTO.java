package com.hai.aiknowledgebase.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * <h2>片段编辑请求 DTO（精细化干预）</h2>
 *
 * <p>用于 {@code PUT /api/knowledge-documents/chunks/{chunkId}} 接口，
 * 管理员可修改单个片段的文本或元数据，字段为 null 时保持原值不变。</p>
 *
 * <h3>可编辑元数据范围</h3>
 * <ul>
 *   <li>{@code contextPrefix}：层级路径（如 "第一章 > 1.1 概述"）</li>
 *   <li>{@code contentType}：内容类型（text/code/table）</li>
 *   <li>{@code parentText}：父级段落全文</li>
 *   <li>{@code parentStart}/{@code parentEnd}：父级段落边界索引</li>
 * </ul>
 * <p>{@code tokenCount} 由服务端根据修改后的文本自动重算，前端无需传入。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentChunkUpdateDTO {

    /** 切片文本（null 表示不修改） */
    private String text;

    /** 层级路径（null 表示不修改） */
    private String contextPrefix;

    /** 内容类型：text/code/table（null 表示不修改） */
    private String contentType;

    /** 父级段落全文（null 表示不修改） */
    private String parentText;

    /** 父级段落起始 chunk 索引（null 表示不修改） */
    private Integer parentStart;

    /** 父级段落结束 chunk 索引（null 表示不修改） */
    private Integer parentEnd;
}