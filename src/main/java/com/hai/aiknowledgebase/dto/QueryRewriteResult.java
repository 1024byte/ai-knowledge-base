package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 查询改写结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryRewriteResult {

    /** 改写后的主查询（用于向量检索） */
    private String rewrittenQuery;

    /** 扩展的关键词列表（用于 BM25 精确匹配） */
    private List<String> expandKeywords;

    /** 排除关键词列表（用于检索过滤，如"不要XX"） */
    private List<String> excludeKeywords;

    /** 置信度 0.0-1.0，用于决定是否降级或跳过后续层级 */
    private double confidence;

    /** 命中的改写路径 */
    private RewritePath path;

    /** 任务分解子查询（LLM 改写时可能产生多个子查询） */
    private List<String> subQueries;

    /** HyDE 假设性答案（可选，用于语义增强） */
    private String hypotheticAnswer;

    /** 是否触发漂移校验拦截 */
    private boolean driftBlocked;

    /** 命中的改写策略（路由分类结果） */
    private RewriteStrategyEnum strategy;

    /** 检索质量评分（0.0-1.0），仅检索后回填，默认 -1 表示未评估 */
    private double retrievalQuality;

    /** 是否发生了实质性的改写 */
    public boolean isRewritten() {
        return rewrittenQuery != null && !rewrittenQuery.isEmpty() && path != RewritePath.NONE;
    }
}