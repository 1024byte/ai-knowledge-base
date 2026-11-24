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

    /**
     * 是否启用查询改写：null=跟随参数管理 queryRewrite.enabled；true/false=本次强制覆盖。
     * <p>开启后先改写再检索，改写结果在响应中回显；召回测试无对话历史，指代消解不会触发。</p>
     */
    private Boolean enableRewrite;

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

    /** 是否启用 Rerank 重排序，hybrid / vector / bm25 三种策略均可挂载 */
    @Builder.Default
    private boolean enableRerank = false;

    /** Rerank 阈值（Cross-Encoder 原始 logits），默认 -4.5，低于该分的片段将被过滤；仅 enableRerank=true 时生效 */
    @Builder.Default
    private Double rerankThreshold = -4.5;

    /** 候选池倍数：开启 Rerank 时召回候选数 = topK × candidateMultiplier，默认 3 */
    @Builder.Default
    private Integer candidateMultiplier = 3;

    /** 候选池上限：开启 Rerank 时召回候选数不超过该值，默认 100 */
    @Builder.Default
    private Integer maxCandidates = 100;

    /**
     * 本次测试的精排候选上限，范围 1~300。
     * 留空表示使用参数管理 rerank.maxDocs（全局/KB），仅影响本次测试，不写库、不影响生产。
     */
    private Integer rerankMaxDocs;

    /** 最终返回数量，默认等于 topK；开启 Rerank 时在精排过滤后截取 */
    private Integer topN;

    /**
     * Rerank 挂载阶段，仅 hybrid 生效：
     * fuse_rerank（默认，先 RRF 融合再精排）/ rerank_fuse（两路各自精排后再 RRF，仅调优实验）
     */
    @Builder.Default
    private String rerankStage = "fuse_rerank";

    /** 排除关键词列表，空 = 不过滤；与智慧问答生产链路的排除词过滤保持一致 */
    @Builder.Default
    private List<String> excludeKeywords = new ArrayList<>();
}