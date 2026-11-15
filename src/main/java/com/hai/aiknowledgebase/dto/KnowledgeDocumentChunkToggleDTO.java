package com.hai.aiknowledgebase.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * <h2>片段启停控制请求 DTO（召回过滤）</h2>
 *
 * <p>用于 {@code PUT /api/knowledge-documents/chunks/enabled} 接口，
 * 批量切换片段的启用状态：{@code enabled=false} 时该片段在检索时被过滤，
 * 不参与召回；{@code enabled=true} 时恢复召回。</p>
 *
 * <h3>实现策略</h3>
 * <p>向量数据始终保留，仅通过检索层过滤实现零成本切换；禁用时同步从 BM25
 * 内存索引移除，启用时重新加入。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentChunkToggleDTO {

    /** 待切换的片段主键列表 */
    private List<Long> chunkIds;

    /** 目标状态：true=启用，false=禁用 */
    private Boolean enabled;
}