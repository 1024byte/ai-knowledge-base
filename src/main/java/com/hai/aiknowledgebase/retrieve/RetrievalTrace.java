package com.hai.aiknowledgebase.retrieve;

import java.util.List;

/**
 * <h3>检索分路观测结果</h3>
 *
 * <p>承载向量 / BM25 / 融合 / 精排四路结果及其耗时，供召回测试映射到响应结构。
 * 各路的 {@code score} 口径：向量=余弦相似度(0~1)，BM25=归一化分数(0~1)，
 * 融合=RRF 累加分数（Rerank 关闭时经 content_type 降权），
 * 精排=Cross-Encoder 原始 logits 经 content_type 降权。</p>
 */
public class RetrievalTrace {
    private final List<RankedResult> vector;
    private final List<RankedResult> bm25;
    private final List<RankedResult> fusion;
    private final List<RerankedResult> rerank;
    private final Long vectorMs;
    private final Long bm25Ms;
    private final Long rerankMs;
    /** 本次是否实际执行了 Rerank 精排（候选非空且精排成功） */
    private final boolean rerankApplied;

    public RetrievalTrace(List<RankedResult> vector,
                          List<RankedResult> bm25,
                          List<RankedResult> fusion,
                          List<RerankedResult> rerank,
                          Long vectorMs, Long bm25Ms, Long rerankMs,
                          boolean rerankApplied) {
        this.vector = vector;
        this.bm25 = bm25;
        this.fusion = fusion;
        this.rerank = rerank;
        this.vectorMs = vectorMs;
        this.bm25Ms = bm25Ms;
        this.rerankMs = rerankMs;
        this.rerankApplied = rerankApplied;
    }

    public List<RankedResult> getVector() { return vector; }
    public List<RankedResult> getBm25() { return bm25; }
    public List<RankedResult> getFusion() { return fusion; }
    public List<RerankedResult> getRerank() { return rerank; }
    public Long getVectorMs() { return vectorMs; }
    public Long getBm25Ms() { return bm25Ms; }
    public Long getRerankMs() { return rerankMs; }
    public boolean isRerankApplied() { return rerankApplied; }
}