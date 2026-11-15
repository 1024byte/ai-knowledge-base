package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <h2>知识库切片参数</h2>
 *
 * <p>聚合切片流程所需的全部知识库级参数，供 {@code KnowledgeDocumentService} 一次性读取，
 * 保证预览、快速切片、正式向量化三处使用同一套参数。</p>
 *
 * <ul>
 *   <li>{@code splitStrategy}：切片策略枚举名（AUTO/RECURSIVE/SENTENCE/PARAGRAPH/LINE），未知值回退 AUTO</li>
 *   <li>{@code chunkLength}：切片长度（token），对应通用切分器的 maxTokens</li>
 *   <li>{@code overlapLength}：重叠长度（token），对应相邻 Chunk 的尾部重叠量</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public class ChunkingParams {

    /** 切片策略枚举名 */
    private final String splitStrategy;

    /** 切片长度（token），可为 null 表示使用默认值 */
    private final Integer chunkLength;

    /** 重叠长度（token），可为 null 表示不重叠 */
    private final Integer overlapLength;
}