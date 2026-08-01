package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.annotation.Timed;
import com.hai.aiknowledgebase.dto.SearchResult;
import com.hai.aiknowledgebase.queryrewrite.QueryRewriteService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <h2>RAG 检索与生成服务</h2>
 *
 * <p>核心职责：编排多策略检索流程，将检索到的文档片段构建为上下文，调用 LLM 生成最终答案。</p>
 *
 * <h3>检索流程（四阶段级联）</h3>
 * <pre>
 * 用户查询 "配置向量数据库"
 *     │
 *     ├── 阶段1：混合检索（向量 + BM25）── 主检索路径，取语义和关键词之长
 *     │
 *     ├── 阶段2：排除关键词过滤 ── 剔除包含排除词的片段（如 "不要XX"）
 *     │
 *     ├── 阶段3：Cross-Encoder Rerank 精排 ── 对候选片段精排，提升 Top-K 精度
 *     │
 *     └──→ 最终按分数降序重排序
 * </pre>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>混合检索</b>：向量语义检索 + BM25 关键词检索，RRF 融合排序</li>
 *   <li><b>Rerank 精排</b>：Cross-Encoder (BGE-Reranker-v2-m3) 对 (query, doc) 联合编码</li>
 *   <li><b>降级机制</b>：Reranker 不可用时跳过精排，排除词过滤全部剔除时保留原始结果</li>
 * </ul>
 *
 * @see HybridSearchService 混合检索服务（向量 + BM25）
 * @see RerankerService Cross-Encoder Rerank 精排服务
 * @see QueryRewriteService 查询改写服务（提供扩展词和排除词）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGSearchService {

    /** LangChain4j 向量存储（PGVector），用于向量相似度检索 */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** LangChain4j 嵌入模型，用于将文本转为向量 */
    private final EmbeddingModel embeddingModel;

    /** LangChain4j 聊天模型（DeepSeek），用于最终答案生成 */
    private final OpenAiChatModel chatModel;

    /** 混合检索服务，并行执行向量检索和 BM25 关键词检索 */
    private final HybridSearchService hybridSearchService;

    /** 中文分词服务，用于排除关键词的词级精确匹配（避免子串误杀） */
    private final ChineseTokenizerService tokenizerService;

    /** Cross-Encoder Rerank 服务，对检索结果精排 */
    private final RerankerService rerankerService;

    /** 默认检索返回数量（无过滤时） */
    private static final int DEFAULT_MAX_RESULTS = 5;

    /** 文件名过滤模式下的检索返回数量（扩大范围以提高命中率） */
    private static final int MAX_RESULTS_WHEN_FILTERED = 20;

    /** 向量检索最低余弦相似度阈值，低于此值的片段视为无关结果（0.6 分仍无关则需 &gt; 0.6） */
    private static final double MIN_VECTOR_SCORE = 0.3;

    // ==================== 核心检索方法 ====================

    /**
     * <h3>检索文档片段（简化版）</h3>
     *
     * <p>不传入扩展词和排除词，仅执行混合检索 + Rerank 精排。</p>
     *
     * @param userQuery 用户原始查询
     * @param topK      返回结果数量上限
     * @return 检索到的文档片段列表
     */
    public List<HybridSearchService.RankedResult> retrieveSegments(String userQuery, int topK) {
        return retrieveSegments(userQuery, topK, null, null);
    }

    /**
     * <h3>检索文档片段（三阶段级联 + 分数重排序）</h3>
     *
     * <h4>阶段1：混合检索</h4>
     * <p>并行执行向量语义检索和 BM25 关键词检索，RRF 融合排序，返回带分数结果。</p>
     *
     * <h4>阶段2：排除关键词过滤</h4>
     * <p>遍历所有结果片段，剔除包含排除关键词的片段，确保不返回用户明确不要的内容。</p>
     *
     * <h4>阶段3：Cross-Encoder Rerank 精排</h4>
     * <p>使用 BGE-Reranker-v2-m3 对候选片段进行 Cross-Encoder 精排，
     * 将 (query, doc) 联合编码，捕捉 Bi-Encoder 无法建模的词间交互，显著提升 Top-K 精度。</p>
     *
     * <h4>最终重排序</h4>
     * <p>所有阶段的结果合并后，按分数降序统一重排序，确保最相关的片段排在前面。</p>
     *
     * @param userQuery       用户原始查询（经查询改写后的 rewrittenQuery）
     * @param topK            返回结果数量上限
     * @param expandKeywords  扩展关键词（已废弃，由阶段1混合检索的BM25覆盖）
     * @param excludeKeywords 排除关键词列表
     * @return 按分数降序排列的带分数文档片段列表
     */
    @Timed("混合检索+Rerank")
    public List<HybridSearchService.RankedResult> retrieveSegments(String userQuery, int topK,
                                              List<String> expandKeywords,
                                              List<String> excludeKeywords) {
        log.info("开始检索，用户查询: {} | 扩展词: {} | 排除词: {}",
                userQuery, expandKeywords, excludeKeywords);

        // ===== 阶段1：混合检索（主路径）=====
        // 并行执行向量检索 + BM25 关键词检索，RRF 融合排序
        // minVectorScore 过滤掉余弦相似度低于阈值的向量结果，避免无关片段进入融合

        List<HybridSearchService.RankedResult> allResults = new ArrayList<>(
                hybridSearchService.hybridSearchRanked(userQuery, topK, MIN_VECTOR_SCORE));

        log.info("混合检索召回 {} 个片段，前5个片段内容: {}", allResults.size(),
                allResults.stream().limit(5)
                        .map(r -> r.getSegment().text())
                        .collect(Collectors.joining(" | ")));

        // ===== 阶段2：排除关键词过滤 =====
        // 对片段分词后精确匹配排除词（避免子串误杀，如排除"Java"误杀"JavaScript"）
        if (excludeKeywords != null && !excludeKeywords.isEmpty()) {
            int beforeFilter = allResults.size();
            List<HybridSearchService.RankedResult> filtered = allResults.stream()
                    .filter(rr -> {
                        String text = rr.getSegment().text();
                        // 对片段文本分词，构建词集合用于精确匹配
                        List<String> segTokens = tokenizerService.tokenize(text, true);
                        Set<String> tokenSet = new HashSet<>(segTokens);
                        // 如果任一排除词作为独立词出现在片段中，则过滤掉
                        for (String exclude : excludeKeywords) {
                            if (tokenSet.contains(exclude)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

            // 降级：过滤后结果为空，保留原始结果并记录警告
            if (filtered.isEmpty() && beforeFilter > 0) {
                log.warn("排除关键词过滤导致所有片段被剔除({} -> 0)，降级保留原始结果", beforeFilter);
            } else {
                allResults = filtered;
            }
            log.info("排除关键词过滤: {} -> {} 个片段", beforeFilter, allResults.size());
        }

        // ===== 阶段3：Cross-Encoder Rerank 精排 =====
        // 使用 BGE-Reranker-v2-m3 对候选片段进行 Cross-Encoder 精排
        // Cross-Encoder 将 (query, doc) 联合编码，捕捉 Bi-Encoder 无法建模的词间交互
        if (rerankerService.isAvailable() && !allResults.isEmpty()) {
            log.info("开始 Rerank 精排，候选片段数: {}", allResults.size());

            List<String> docTexts = allResults.stream()
                    .map(r -> r.getSegment().text())
                    .collect(Collectors.toList());

            List<RerankerService.RerankResult> rerankResults =
                    rerankerService.rerank(userQuery, docTexts, allResults.size());

            // 按 Rerank 分数重建结果列表
            List<HybridSearchService.RankedResult> reranked = new ArrayList<>();
            for (RerankerService.RerankResult rr : rerankResults) {
                HybridSearchService.RankedResult original = allResults.get(rr.getIndex());
                // 用 Rerank 分数替换原始分数
                reranked.add(new HybridSearchService.RankedResult(original.getSegment(), rr.getScore()));
            }
            allResults = reranked;

            log.info("Rerank 精排完成，Top-3 分数: {}",
                    allResults.stream().limit(3)
                            .map(r -> String.format("%.4f", r.getScore()))
                            .collect(Collectors.joining(", ")));
        } else if (rerankerService.isEnabled() && !rerankerService.isAvailable()) {
            log.info("Reranker 已启用但未初始化，跳过精排");
        }

        // ===== 最终重排序：按分数降序统一排序 =====
        // 阶段1(RRF分数) + 阶段2(向量相似度) 的结果混合后，按分数统一重排序
        // 确保最相关的片段排在前面，缓解 LLM "lost in the middle" 问题
        allResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        log.info("分数重排序完成，Top-3 分数: {}",
                allResults.stream().limit(3)
                        .map(r -> String.format("%.4f", r.getScore()))
                        .collect(Collectors.joining(", ")));

        // ===== 阶段4：分数阈值过滤 =====
        // 剔除 Rerank 分数过低的片段，避免噪音进入 LLM 上下文
        int beforeFilter = allResults.size();
        List<HybridSearchService.RankedResult> filtered = allResults.stream()
                .filter(r -> r.getScore() >= rerankerService.getMinScore())
                .collect(Collectors.toList());
        if (filtered.isEmpty() && beforeFilter > 0) {
            log.warn("分数阈值过滤剔除了所有片段({} -> 0)，降级保留全部", beforeFilter);
        } else {
            allResults = filtered;
        }
        log.info("分数阈值过滤: {} -> {} 个片段 (阈值={})", beforeFilter, allResults.size(),
                String.format("%.2f", rerankerService.getMinScore()));

        log.info("最终返回 {} 个片段", allResults.size());
        return allResults;
    }
}