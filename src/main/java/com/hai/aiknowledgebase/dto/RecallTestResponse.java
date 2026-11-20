package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 召回测试响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecallTestResponse {

    /** 回显本次测试问题 */
    private String query;

    /** 本次检索策略 */
    private String strategy;

    /** 总耗时（毫秒） */
    private Long totalMs;

    /** 向量检索耗时（毫秒），仅 hybrid 时有值 */
    private Long vectorMs;

    /** BM25 检索耗时（毫秒），仅 hybrid 时有值 */
    private Long bm25Ms;

    /** Rerank 重排序耗时（毫秒），未开启 Rerank 时为 null */
    private Long rerankMs;

    /** BM25 分词结果，vector 策略为空数组 */
    private List<String> tokens;

    /** 三路召回结果 */
    private Results results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Results {

        /** 融合结果（RRF 排序），仅 hybrid 时有值 */
        @Builder.Default
        private List<RecallResultItem> fusion = new ArrayList<>();

        /** 向量召回结果，hybrid / vector 时有值 */
        @Builder.Default
        private List<RecallResultItem> vector = new ArrayList<>();

        /** BM25 召回结果，hybrid / bm25 时有值 */
        @Builder.Default
        private List<RecallResultItem> bm25 = new ArrayList<>();

        /** 重排结果列表，仅 hybrid 且 enableRerank=true 时有值，否则为空数组 */
        @Builder.Default
        private List<RecallResultItem> rerank = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecallResultItem {

        /** 片段 ID（真实 DB 主键） */
        private Long chunkId;

        /** 文档 ID */
        private Long documentId;

        /** 来源文档名（含扩展名） */
        private String source;

        /** 所属知识库名称 */
        private String kbName;

        /** 片段文本内容 */
        private String content;

        /** 得分：fusion 为 RRF 分数，vector/bm25 为相似度（0~1） */
        private Double score;

        /** 向量召回排名，仅 fusion 列表填充，单路未命中为 null */
        private Integer vectorRank;

        /** BM25 召回排名，仅 fusion 列表填充，单路未命中为 null */
        private Integer bm25Rank;

        /** Rerank 精排得分（Cross-Encoder 输出，非 0~1 相似度） */
        private Double rerankScore;

        /** 粗排排名（原 RRF/fusion 排名，1-based） */
        private Integer coarseRank;

        /** 精排排名（Rerank 后排名，1-based） */
        private Integer rerankRank;
    }
}