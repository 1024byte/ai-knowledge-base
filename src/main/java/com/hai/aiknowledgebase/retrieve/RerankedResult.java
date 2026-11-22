package com.hai.aiknowledgebase.retrieve;

import dev.langchain4j.data.segment.TextSegment;

/**
 * Rerank 精排结果：保留精排分数与在融合列表中的原始下标（粗排位次）。
 */
public class RerankedResult {
    private final TextSegment segment;
    private final double rerankScore;
    private final int coarseRank;

    public RerankedResult(TextSegment segment, double rerankScore, int coarseRank) {
        this.segment = segment;
        this.rerankScore = rerankScore;
        this.coarseRank = coarseRank;
    }

    public TextSegment getSegment() { return segment; }
    public double getRerankScore() { return rerankScore; }
    public int getCoarseRank() { return coarseRank; }
}