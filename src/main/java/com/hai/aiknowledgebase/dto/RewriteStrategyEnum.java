package com.hai.aiknowledgebase.dto;

/**
 * 按需改写策略枚举
 *
 * <p>由 {@link com.hai.aiknowledgebase.queryrewrite.QueryRouter} 根据查询特征自动选择，
 * 提供细粒度的改写路由控制。</p>
 *
 * @see com.hai.aiknowledgebase.queryrewrite.QueryRouter 路由分类器
 */
public enum RewriteStrategyEnum {

    /** 直接检索：不纠错、不消解、不改写，原始查询直达检索 */
    DIRECT,

    /** 纠错+消解：纠正错别字 + 指代消解，然后直接检索 */
    RESOLVE_ONLY,

    /** 简单改写：纠错 + 消解 + LLM 改写 */
    SIMPLE_REWRITE
}