package com.hai.aiknowledgebase.interfaces;

import com.hai.aiknowledgebase.dto.QueryRewriteResult;

/**
 * L3 大模型查询改写器接口
 * 定义跨轮语义补全、意图扩展等高级改写能力
 */
@FunctionalInterface
public interface LocalQueryRewriter {

    /**
     * 执行基于大模型的查询改写
     * @param originalQuery 原始用户查询
     * @return 改写结果（含改写后的文本和扩展关键词）
     */
    QueryRewriteResult rewrite(String originalQuery);
}