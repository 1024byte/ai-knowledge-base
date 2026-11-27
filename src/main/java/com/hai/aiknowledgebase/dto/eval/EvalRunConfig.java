package com.hai.aiknowledgebase.dto.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测运行配置快照。
 *
 * <p>字段与召回测试请求一一对应，保证「复现到召回测试」时配置完整。
 * 其中 {@code threshold} / {@code rrfK} 为后端增补字段（前端文档未列出）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class EvalRunConfig {

    /** 检索策略：hybrid / vector / bm25 */
    private String strategy;

    /** 每路返回数量 */
    private Integer topK;

    /** 相似度阈值 */
    private Double threshold;

    /** RRF 平滑系数 */
    private Integer rrfK;

    /** 是否启用 Rerank */
    private Boolean enableRerank;

    /** Rerank 挂载阶段：fuse_rerank / rerank_fuse */
    private String rerankStage;

    /** Rerank 分数阈值 */
    private Double rerankThreshold;

    /** 精排候选上限 */
    private Integer rerankMaxDocs;

    /** 候选池倍数 */
    private Integer candidateMultiplier;

    /** 候选池上限 */
    private Integer maxCandidates;

    /** 最终返回数量 */
    private Integer topN;

    /** 是否启用查询改写（前端已展开为布尔，后端不做"跟随"语义） */
    private Boolean enableRewrite;

    /** 排除关键词 */
    @Builder.Default
    private List<String> excludeKeywords = new ArrayList<>();
}
