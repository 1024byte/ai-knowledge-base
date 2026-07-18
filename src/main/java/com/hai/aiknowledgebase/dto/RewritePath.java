package com.hai.aiknowledgebase.dto;

/**
 * 查询改写路径枚举
 */
public enum RewritePath {
    /** 未改写，使用原始查询 */
    NONE,
    /** L1 规则改写 */
    L1_RULE,
    /** L2 NLP 改写 */
    L2_NLP,
    /** L3 上下文补全 */
    L3_CONTEXT_COMPLETION,
    /** L3 任务分解（Phase 2） */
    L3_TASK_DECOMPOSITION,
    /** L3 HyDE 语义增强（Phase 2） */
    L3_HYDE
}
