package com.hai.aiknowledgebase.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * <h2>保存切片请求 DTO</h2>
 *
 * <p>用于 {@code PUT /api/knowledge-documents/{id}/chunks} 接口的请求体，
 * 在原有切片列表基础上新增 {@code confirm} 确认标记。</p>
 *
 * <h3>确认机制</h3>
 * <p>当文档已向量化（isVectorized == 2）时，前端需先带 {@code confirm=false}
 * 提交，后端返回提示；用户确认后带 {@code confirm=true} 重试，才真正执行
 * 保存并清理向量化数据。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentChunksSaveDTO {

    /** 前端审校后的切片列表（全量覆盖） */
    private List<KnowledgeDocumentChunksVO.ChunkItem> chunks;

    /** 用户确认标记：已向量化文档重新保存切片需置为 true */
    private Boolean confirm;
}