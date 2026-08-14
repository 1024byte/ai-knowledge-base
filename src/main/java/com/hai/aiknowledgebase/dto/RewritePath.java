package com.hai.aiknowledgebase.dto;

/**
 * 查询改写路径枚举
 */
public enum RewritePath {
    /** 未改写，使用原始查询 */
    NONE,
    /** L1 规则改写（固定映射 + 同义词替换） */
    L1_RULE,
    /** 本地 LLM 改写 */
    LLM_REWRITE
}