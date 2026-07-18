package com.hai.aiknowledgebase.dto;

/**
 * 意图路由决策枚举
 */
public enum RoutingDecision {
    /** 跳过全部改写，直达检索 */
    SKIP,
    /** 最多走到 L2，不触发 L3 */
    RULE_ONLY,
    /** 允许走完整瀑布流至 L3 */
    FULL
}
