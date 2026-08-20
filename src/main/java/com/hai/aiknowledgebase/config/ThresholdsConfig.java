package com.hai.aiknowledgebase.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <h2>统一阈值配置</h2>
 *
 * <p>将系统中所有相似度/分数阈值统一收敛到 {@code thresholds.*} 命名空间下，
 * 便于集中管理和调优。涵盖检索、精排、切分、查询改写、纠错五个维度的阈值。</p>
 *
 * <h3>配置结构</h3>
 * <pre>
 * thresholds:
 *   retrieval:
 *     vector-min-score: 0.6       # 向量检索最低余弦相似度阈值
 *   reranker:
 *     min-score: -4.5             # Rerank 最低分数阈值
 *   chunking:
 *     semantic: 0.6               # 语义切分默认阈值
 *     technical: 0.7              # 技术文档语义切分阈值
 *     legal: 0.75                 # 法律文档语义切分阈值
 *     table-heavy: 0.7            # 表格密集文档语义切分阈值
 *     general: 0.7                # 通用文档语义切分阈值
 *     exam-paper: 0.7             # 试卷文档语义切分阈值
 *   query-rewrite:
 *     fidelity: 0.75              # 改写保真度阈值（cosine similarity）
 *     l1-confidence: 0.85         # L1 规则改写置信度阈值
 *     l2-confidence: 0.80         # L2 规则改写置信度阈值
 *   corrector:
 *     l1: 0.85                    # L1 word-checker 置信度阈值
 *     l2: 0.80                    # L2 拼音匹配置信度阈值
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "thresholds")
public class ThresholdsConfig {

    private Retrieval retrieval = new Retrieval();
    private Reranker reranker = new Reranker();
    private Chunking chunking = new Chunking();
    private QueryRewrite queryRewrite = new QueryRewrite();
    private Corrector corrector = new Corrector();

    // ======================== 内部类：各维度阈值 ========================

    @Data
    public static class Retrieval {
        /** 向量检索最低余弦相似度阈值（0.0~1.0），低于此值的片段视为无关结果 */
        private double vectorMinScore = 0.6;
    }

    @Data
    public static class Reranker {
        /** Rerank 最低分数阈值（Cross-Encoder 输出），低于此分视为不相关，不送入 LLM */
        private double minScore = -4.5;
    }

    @Data
    public static class Chunking {
        /** 语义切分默认阈值（0.0~1.0），句子与累积向量相似度低于此值时触发切分 */
        private double semantic = 0.6;
        /** 技术文档语义切分阈值 */
        private double technical = 0.7;
        /** 法律文档语义切分阈值 */
        private double legal = 0.75;
        /** 表格密集文档语义切分阈值 */
        private double tableHeavy = 0.7;
        /** 通用文档语义切分阈值 */
        private double general = 0.7;
        /** 试卷文档语义切分阈值 */
        private double examPaper = 0.7;
    }

    @Data
    public static class QueryRewrite {
        /** 改写保真度阈值（cosine similarity），改写后向量与原查询相似度低于此值时丢弃改写结果 */
        private double fidelity = 0.75;
        /** L1 规则改写置信度阈值 */
        private double l1Confidence = 0.85;
        /** L2 规则改写置信度阈值 */
        private double l2Confidence = 0.80;
    }

    @Data
    public static class Corrector {
        /** L1 word-checker 纠错置信度阈值 */
        private double l1 = 0.85;
        /** L2 拼音匹配纠错置信度阈值 */
        private double l2 = 0.80;
    }
}