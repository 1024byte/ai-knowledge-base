package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 查询改写结果（V3.0 扩展版）
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

    /** 任务分解子查询（仅 L3_TASK_DECOMPOSITION 路径有值，Phase 2） */
    private List<String> subQueries;

    /** HyDE 假设性答案（仅 L3_HYDE 路径有值，Phase 2） */
    private String hypotheticAnswer;

    /** 是否触发漂移校验拦截 */
    private boolean driftBlocked;

    /** LLM 消耗的 Token 数（仅 L3 路径有值，用于埋点） */
    private Integer tokenUsage;

    /** 是否发生了实质性的改写 */
    public boolean isRewritten() {
        return rewrittenQuery != null && !rewrittenQuery.isEmpty() && path != RewritePath.NONE;
    }
}
