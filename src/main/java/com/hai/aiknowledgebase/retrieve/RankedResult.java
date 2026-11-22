package com.hai.aiknowledgebase.retrieve;

import dev.langchain4j.data.segment.TextSegment;

/**
 * <h3>排序结果包装类</h3>
 *
 * <p>将文本片段与其检索分数绑定在一起，供跨阶段统一重排序使用。</p>
 *
 * <p>分数含义取决于检索路径：</p>
 * <ul>
 *   <li>向量检索路径：score 为余弦相似度（0.0 ~ 1.0）</li>
 *   <li>关键词检索路径：score 为 BM25 分数（无上限）</li>
 *   <li>RRF 融合后：score 为 RRF 累加分数</li>
 *   <li>文件名过滤检索：score 为余弦相似度（0.0 ~ 1.0）</li>
 * </ul>
 */
public class RankedResult {
    /** 文本片段，包含文档内容和元数据 */
    private final TextSegment segment;
    /** 检索分数（向量相似度 / BM25分数 / RRF融合分数） */
    private final double score;
    /** 原始向量相似度（0~1），无向量路径命中时为 null */
    private final Double vectorScore;
    /** 原始 BM25 分数，无关键词路径命中时为 null */
    private final Double bm25Score;

    public RankedResult(TextSegment segment, double score) {
        this(segment, score, null, null);
    }

    public RankedResult(TextSegment segment, double score, Double vectorScore, Double bm25Score) {
        this.segment = segment;
        this.score = score;
        this.vectorScore = vectorScore;
        this.bm25Score = bm25Score;
    }

    public TextSegment getSegment() { return segment; }
    public double getScore() { return score; }
    public Double getVectorScore() { return vectorScore; }
    public Double getBm25Score() { return bm25Score; }
}