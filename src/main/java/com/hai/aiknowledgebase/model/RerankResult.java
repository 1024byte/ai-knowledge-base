package com.hai.aiknowledgebase.model;

/**
 * Rerank 结果（顶级类，供接口与各实现共用）。
 */
public class RerankResult {

    /** 文档在原始候选列表中的索引 */
    private final int index;
    /** 文档文本 */
    private final String text;
    /** 相关性分数 */
    private final double score;

    public RerankResult(int index, String text, double score) {
        this.index = index;
        this.text = text;
        this.score = score;
    }

    public int getIndex() {
        return index;
    }

    public String getText() {
        return text;
    }

    public double getScore() {
        return score;
    }
}
