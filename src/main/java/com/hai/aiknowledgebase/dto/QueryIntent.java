package com.hai.aiknowledgebase.dto;

/**
 * 查询意图枚举
 * <p>
 * 用于 L2 NLP 层的意图识别，指导改写策略和置信度计算
 */
public enum QueryIntent {

    /** 事实查询：包含"多少"、"几个"、"是否"、"能不能"等疑问词 */
    FACTUAL,

    /** 操作步骤查询：包含"如何"、"怎么"、"怎样"、"步骤"、"方法"等 */
    PROCEDURAL,

    /** 对比查询：包含"区别"、"对比"、"不同"、"差异"、"vs"等 */
    COMPARISON,

    /** 定义查询：包含"什么是"、"定义"、"含义"、"意思"等 */
    DEFINITIONAL,

    /** 模糊查询：代词占比高或关键词过少 */
    AMBIGUOUS
}
