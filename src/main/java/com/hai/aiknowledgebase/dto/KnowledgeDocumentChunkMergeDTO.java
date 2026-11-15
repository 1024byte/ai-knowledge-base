package com.hai.aiknowledgebase.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * <h2>片段合并请求 DTO（精细化干预）</h2>
 *
 * <p>用于 {@code POST /api/knowledge-documents/chunks/merge} 接口，
 * 将多个相邻片段按 {@code chunkIds} 顺序合并为单个片段，
 * 合并文本为各片段文本的拼接（用换行符分隔），token 数由服务端重算。</p>
 *
 * <h3>约束</h3>
 * <ul>
 *   <li>{@code chunkIds} 至少 2 个片段</li>
 *   <li>所有片段必须属于 {@code documentId} 指定的文档</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentChunkMergeDTO {

    /** 所属文档 ID（用于权限与归属校验） */
    private Long documentId;

    /** 待合并的片段主键列表（按合并顺序排列，至少 2 个） */
    private List<Long> chunkIds;
}