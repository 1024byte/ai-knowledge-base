package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 召回测试请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecallTestRequest {

    /** 测试问题，最多 200 字 */
    private String query;

    /** 待检索知识库 ID 列表，至少 1 个 */
    private List<Long> kbIds;

    /** 待检索文档 ID 列表，空 = 所选知识库下全部文档 */
    @Builder.Default
    private List<Long> documentIds = new ArrayList<>();

    /** 检索策略：hybrid / vector / bm25 */
    @Builder.Default
    private String strategy = "hybrid";

    /** 每路返回数量，范围 1~50 */
    @Builder.Default
    private Integer topK = 5;

    /** 相似度阈值，范围 0~1 */
    @Builder.Default
    private Double threshold = 0.3;

    /** RRF 平滑系数 K，仅 hybrid 生效，范围 1~1000 */
    @Builder.Default
    private Integer rrfK = 60;

    /** 是否启用 Rerank 重排序，仅 hybrid 生效，vector/bm25 策略下忽略 */
    @Builder.Default
    private boolean enableRerank = false;

    /** Rerank 阈值，范围 0~1，低于该 Rerank 得分的片段将被过滤；仅 enableRerank=true 时生效 */
    @Builder.Default
    private Double rerankThreshold = 0.5;
}