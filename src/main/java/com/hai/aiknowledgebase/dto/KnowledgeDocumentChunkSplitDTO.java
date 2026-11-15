package com.hai.aiknowledgebase.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * <h2>片段拆分请求 DTO（精细化干预）</h2>
 *
 * <p>用于 {@code POST /api/knowledge-documents/chunks/split} 接口，
 * 将单个片段拆分为多个片段，原片段被删除，拆分后的文本由 {@code texts} 提供。</p>
 *
 * <h3>约束</h3>
 * <ul>
 *   <li>{@code texts} 至少 2 段文本</li>
 *   <li>拆分后的片段按 {@code texts} 顺序依次继承原片段的元数据（contentType/contextPrefix 等）</li>
 *   <li>每个新片段 token 数由服务端重算</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentChunkSplitDTO {

    /** 所属文档 ID（用于权限与归属校验） */
    private Long documentId;

    /** 待拆分的片段主键 */
    private Long chunkId;

    /** 拆分后的文本列表（至少 2 段） */
    private List<String> texts;
}