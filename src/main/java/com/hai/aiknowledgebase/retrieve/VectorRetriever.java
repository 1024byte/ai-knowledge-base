package com.hai.aiknowledgebase.retrieve;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <h2>向量检索原语</h2>
 *
 * <p>封装 PGVector 向量语义检索的底层调用，只负责「把查询向量化 → 检索 → 转换为带分数的
 * {@link RankedResult}」，不包含 RRF 融合、Rerank 精排、降权等编排逻辑。</p>
 *
 * <p>检索时统一过滤两类无效片段（由 {@link ChunkIndexManager} 维护）：
 * 被禁用的片段、未完成向量化的片段。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorRetriever {

    /** LangChain4j 嵌入模型，用于将查询文本转为向量 */
    private final EmbeddingModel embeddingModel;

    /** LangChain4j 向量存储（PGVector），用于向量相似度检索 */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** 片段索引管理器，提供禁用/未完成向量化片段集合用于过滤 */
    private final ChunkIndexManager chunkIndexManager;

    /**
     * <h3>纯向量检索</h3>
     *
     * <p>仅使用向量语义相似度进行检索，不涉及关键词匹配。</p>
     *
     * @param query 用户查询文本
     * @param topK  返回结果数量上限
     * @return 按余弦相似度降序排列的 TextSegment 列表
     */
    public List<TextSegment> vectorSearch(String query, int topK) {
        return vectorSearch(query, topK, 0.0);
    }

    /**
     * <h3>纯向量检索（带相关性阈值）</h3>
     *
     * <p>仅返回分数 &ge; minScore 的结果，避免无关查询返回低质量片段。</p>
     *
     * @param query    用户查询文本
     * @param topK     返回结果数量上限
     * @param minScore 最低余弦相似度阈值（0.0~1.0），低于此值的片段将被过滤
     * @return 按余弦相似度降序排列的 TextSegment 列表
     */
    public List<TextSegment> vectorSearch(String query, int topK, double minScore) {
        return vectorSearchRanked(query, topK).stream()
                .filter(r -> r.getScore() >= minScore)
                .map(RankedResult::getSegment)
                .collect(Collectors.toList());
    }

    /**
     * <h3>向量检索（带分数）</h3>
     *
     * @param query 用户查询文本
     * @param topK  返回结果数量上限
     * @return 带相似度分数的排序结果列表
     */
    public List<RankedResult> vectorSearchRanked(String query, int topK) {
        return vectorSearchRanked(query, topK, null);
    }

    public List<RankedResult> vectorSearchRanked(String query, int topK, Set<Long> kbIds) {
        return vectorSearchRanked(query, topK, kbIds, null);
    }

    /**
     * <h3>向量检索（带分数，支持知识库与文档范围过滤）</h3>
     *
     * <p>在 kbIds 知识库隔离基础上，增加 documentIds 文档范围过滤，二者取交集。
     * documentIds 为 null/空集合时表示不过滤文档范围。</p>
     *
     * @param query       用户查询文本
     * @param topK        返回结果数量上限
     * @param kbIds       知识库范围（null 表示不过滤；空集合表示无可见知识库）
     * @param documentIds 文档范围（null/空 表示不过滤）
     * @return 带相似度分数的排序结果列表
     */
    public List<RankedResult> vectorSearchRanked(String query, int topK, Set<Long> kbIds, Set<Long> documentIds) {
        // 1. 前置校验
        if (query == null || query.trim().isEmpty()) {
            log.warn("Query is empty, return empty result");
            return Collections.emptyList();
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0");
        }

        try {
            // 2. 嵌入（可考虑超时）
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            // 3. 构建请求：多取一部分（3 倍 topK），以补偿后续禁用片段过滤造成的数量缩减
            int fetchSize = Math.max(topK * 3, 30);
            var requestBuilder = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(fetchSize);
            // 检索范围 = kbIds ∩ documentIds：
            //   kbIds == null 表示不过滤知识库；空集合表示无可见知识库（返回空）
            //   documentIds 为 null/空集合表示不过滤文档范围
            Filter filter = null;
            if (kbIds != null) {
                if (kbIds.isEmpty()) {
                    return Collections.emptyList();
                }
                List<String> kbIdValues = kbIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.toList());
                filter = MetadataFilterBuilder.metadataKey("kb_id").isIn(kbIdValues);
            }
            if (documentIds != null && !documentIds.isEmpty()) {
                List<String> docIdValues = documentIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.toList());
                Filter docFilter = MetadataFilterBuilder.metadataKey("document_id").isIn(docIdValues);
                filter = (filter == null) ? docFilter : filter.and(docFilter);
            }
            if (filter != null) {
                requestBuilder.filter(filter);
            }
            EmbeddingSearchRequest request = requestBuilder.build();

            // 4. 检索
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();
            if (matches == null || matches.isEmpty()) {
                return Collections.emptyList();
            }

            // 5. 转换、过滤空值与禁用片段，同时显式排序并截断到 topK
            List<RankedResult> results = matches.stream()
                    .filter(m -> m != null && m.embedded() != null)
                    .filter(m -> !chunkIndexManager.isChunkDisabled(m.embedded()))
                    .filter(m -> !chunkIndexManager.isChunkNotCompleted(m.embedded()))
                    .map(m -> new RankedResult(m.embedded(), m.score(), m.score(), null))
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore())) // 降序
                    .limit(topK)
                    .collect(Collectors.toList());

            log.debug("Vector search for '{}' returned {} results", query, results.size());
            return results;
        } catch (Exception e) {
            log.error("Vector search failed for query='{}'", query, e);
            throw new RuntimeException("Vector search failed", e);
        }
    }
}
