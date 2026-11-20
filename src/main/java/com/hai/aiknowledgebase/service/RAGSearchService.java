package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.annotation.Timed;
import com.hai.aiknowledgebase.config.ThresholdsConfig;
import com.hai.aiknowledgebase.dto.SearchResult;
import com.hai.aiknowledgebase.exception.BusinessException;
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
import java.util.concurrent.CompletableFuture;
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

    /** 父上下文扩展器，用入库时预计算的 parent_text 替换 chunk 文本 */
    private final ParentContextExpander parentContextExpander;

    /** 默认检索返回数量（无过滤时） */
    private static final int DEFAULT_MAX_RESULTS = 5;

    /** 文件名过滤模式下的检索返回数量（扩大范围以提高命中率） */
    private static final int MAX_RESULTS_WHEN_FILTERED = 20;

    private final ThresholdsConfig thresholdsConfig; // 阈值配置

    /** 向量检索最低余弦相似度阈值，由 ThresholdsConfig 统一管理 */
    private double getMinVectorScore() {
        return thresholdsConfig.getRetrieval().getVectorMinScore();
    }

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
        return retrieveSegments(userQuery, topK, -1, null, null, null);
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
     * @param minScore        最低相似度阈值（0.0~1.0），≤0 时使用服务端默认值
     * @param expandKeywords  扩展关键词（已废弃，由阶段1混合检索的BM25覆盖）
     * @param excludeKeywords 排除关键词列表
     * @return 按分数降序排列的带分数文档片段列表
     */
    public List<HybridSearchService.RankedResult> retrieveSegments(String userQuery, int topK,
                                              double minScore,
                                              List<String> expandKeywords,
                                              List<String> excludeKeywords) {
        return retrieveSegments(userQuery, topK, minScore, expandKeywords, excludeKeywords, null);
    }

    @Timed("混合检索+Rerank")
    public List<HybridSearchService.RankedResult> retrieveSegments(String userQuery, int topK,
                                              double minScore,
                                              List<String> expandKeywords,
                                              List<String> excludeKeywords,
                                              Set<Long> kbIds) {
        double effectiveMinScore = (minScore > 0) ? minScore : getMinVectorScore();
        log.info("开始检索，用户查询: {} | topK: {} | minScore: {} | 扩展词: {} | 排除词: {} | kbIds: {}",
                userQuery, topK, effectiveMinScore, expandKeywords, excludeKeywords, kbIds);

        // ===== 阶段1：混合检索（主路径）=====
        // 并行执行向量检索 + BM25 关键词检索，RRF 融合排序
        // minVectorScore 过滤掉余弦相似度低于阈值的向量结果，避免无关片段进入融合

        List<HybridSearchService.RankedResult> allResults = new ArrayList<>(
                hybridSearchService.hybridSearchRanked(userQuery, topK, effectiveMinScore, kbIds));

        log.info("混合检索召回 {} 个片段", allResults.size());

        // ===== 阶段1.5：父上下文扩展 =====
        // 用入库时预计算的 parent_text 替换 chunk 文本，实现零延迟的上下文补全。
        // 解决 PDF 解析将编号列表/长段落误切分导致的碎片化问题。
        allResults = parentContextExpander.expand(allResults);

        // ===== 阶段2：排除关键词过滤 =====
        // 对片段分词后精确匹配排除词（避免子串误杀，如排除"Java"误杀"JavaScript"）
        allResults = filterByExcludeKeywords(allResults, excludeKeywords);

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

        // ===== 阶段5：content_type 降权 =====
        // 对非正文内容（题目、词汇表、答案）降低分数，确保正文优先
        allResults = applyContentTypeWeighting(allResults);

        log.info("最终返回 {} 个片段", allResults.size());
        return allResults;
    }

    /**
     * <h3>根据 content_type 对片段降权</h3>
     *
     * <p>试卷/试题文档中，正文（PASSAGE）权重保持不变，题目（QUESTION）、
     * 词汇表（VOCAB）、答案（ANSWER）的分数乘以折扣系数，确保检索时正文优先。</p>
     *
     * <h4>权重系数</h4>
     * <table>
     *   <tr><th>content_type</th><th>系数</th><th>说明</th></tr>
     *   <tr><td>PASSAGE</td><td>1.0</td><td>正文，不过滤</td></tr>
     *   <tr><td>QUESTION</td><td>0.3</td><td>选择题，大幅降权</td></tr>
     *   <tr><td>VOCAB</td><td>0.3</td><td>词汇表，大幅降权</td></tr>
     *   <tr><td>ANSWER</td><td>0.5</td><td>答案/解析，中度降权</td></tr>
     *   <tr><td>MIXED</td><td>0.7</td><td>混合内容，轻微降权</td></tr>
     *   <tr><td>无标记</td><td>1.0</td><td>非试题文档，不过滤</td></tr>
     * </table>
     *
     * @param results 已排序的检索结果
     * @return 降权后的结果列表
     */
    private List<HybridSearchService.RankedResult> applyContentTypeWeighting(
            List<HybridSearchService.RankedResult> results) {
        List<HybridSearchService.RankedResult> weighted = new ArrayList<>();
        int passageCount = 0;
        int nonPassageCount = 0;

        for (HybridSearchService.RankedResult result : results) {
            String contentType = getContentTypeOf(result.getSegment());

            double weight = getContentTypeWeight(contentType);
            double newScore = result.getScore() * weight;

            weighted.add(new HybridSearchService.RankedResult(result.getSegment(), newScore));

            if ("passage".equals(contentType) || contentType == null) {
                passageCount++;
            } else {
                nonPassageCount++;
            }
        }

        // 按新分数重新排序
        weighted.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        if (nonPassageCount > 0) {
            log.info("content_type 降权: 正文/无标记={} 个, 非正文={} 个 (题目/词汇/答案)",
                    passageCount, nonPassageCount);
        }
        return weighted;
    }

    /**
     * 获取内容类型的分数权重系数。
     */
    private double getContentTypeWeight(String contentType) {
        if (contentType == null) {
            return 1.0;  // 无标记，保持原分
        }
        return switch (contentType.toLowerCase()) {
            case "passage" -> 1.0;
            case "question" -> 0.3;
            case "vocab" -> 0.3;
            case "answer" -> 0.5;
            case "mixed" -> 0.7;
            default -> 1.0;
        };
    }

    /**
     * 安全读取片段元数据中的 content_type（无标记时返回 null）。
     */
    private String getContentTypeOf(TextSegment segment) {
        try {
            return segment.metadata().getString("content_type");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * <h3>对 Rerank 精排结果执行 content_type 降权</h3>
     *
     * <p>与 {@link #applyContentTypeWeighting} 逻辑一致，作用于 {@link RetrievalTrace.RerankedResult}
     * 列表：按 content_type 权重乘以精排分数后降序重排，coarseRank（粗排位次）保持不变。</p>
     */
    private List<RetrievalTrace.RerankedResult> applyContentTypeWeightingToReranked(
            List<RetrievalTrace.RerankedResult> results) {
        if (results.isEmpty()) {
            return results;
        }
        List<RetrievalTrace.RerankedResult> weighted = new ArrayList<>();
        int passageCount = 0;
        int nonPassageCount = 0;

        for (RetrievalTrace.RerankedResult rr : results) {
            String contentType = getContentTypeOf(rr.getSegment());
            double weight = getContentTypeWeight(contentType);
            weighted.add(new RetrievalTrace.RerankedResult(
                    rr.getSegment(), rr.getRerankScore() * weight, rr.getCoarseRank()));

            if ("passage".equalsIgnoreCase(contentType) || contentType == null) {
                passageCount++;
            } else {
                nonPassageCount++;
            }
        }

        // 按降权后的精排分数重新排序
        weighted.sort((a, b) -> Double.compare(b.getRerankScore(), a.getRerankScore()));

        if (nonPassageCount > 0) {
            log.info("content_type 降权(精排后): 正文/无标记={} 个, 非正文={} 个 (题目/词汇/答案)",
                    passageCount, nonPassageCount);
        }
        return weighted;
    }

    /**
     * <h3>排除关键词过滤</h3>
     *
     * <p>对片段分词后精确匹配排除词（避免子串误杀，如排除"Java"误杀"JavaScript"）。
     * 若过滤后结果为空，则降级保留原始结果，避免全部剔除导致无内容可返回。</p>
     *
     * @param results         待过滤的检索结果
     * @param excludeKeywords 排除关键词列表（null/空 表示不过滤）
     * @return 过滤后的结果列表
     */
    private List<HybridSearchService.RankedResult> filterByExcludeKeywords(
            List<HybridSearchService.RankedResult> results, List<String> excludeKeywords) {
        if (excludeKeywords == null || excludeKeywords.isEmpty() || results.isEmpty()) {
            return results;
        }
        int beforeFilter = results.size();
        List<HybridSearchService.RankedResult> filtered = results.stream()
                .filter(rr -> {
                    String text = rr.getSegment().text();
                    List<String> segTokens = tokenizerService.tokenize(text, true);
                    Set<String> tokenSet = new HashSet<>(segTokens);
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
            return results;
        }
        log.info("排除关键词过滤: {} -> {} 个片段", beforeFilter, filtered.size());
        return filtered;
    }

    // ==================== 分路观测检索（召回测试复用） ====================

    /**
     * <h3>带分路观测的检索（供召回测试复用）</h3>
     *
     * <p>将向量 / BM25 单路检索、RRF 融合、Rerank 精排统一编排在本服务内，
     * 复用 {@link HybridSearchService} 的底层检索原语与 {@link RerankerService} 精排，
     * 避免召回测试独立实现重复逻辑，保证观测行为与生产检索链的底层一致。</p>
     *
     * <p>融合结果依次经过生产链路的父上下文扩展（parent_text）、排除关键词过滤，
     * 最终结果（Rerank 开启时为精排结果，否则为融合结果）经过 content_type 降权，
     * 确保召回测试观测到的检索行为与智慧问答生产链路一致。</p>
     *
     * @param query           查询文本
     * @param strategy        检索策略：hybrid / vector / bm25
     * @param topK            每路返回数量上限
     * @param threshold       相似度阈值（0~1）：向量按余弦相似度过滤，BM25 按归一化分数过滤
     * @param kbIds           知识库范围（null 表示不过滤）
     * @param documentIds     文档范围（null/空 表示不过滤）
     * @param rrfK            RRF 平滑系数
     * @param enableRerank    是否启用 Rerank 精排（仅 hybrid 生效）
     * @param rerankThreshold Rerank 分数阈值（Cross-Encoder 原始 logits）
     * @param excludeKeywords 排除关键词列表（null/空 表示不过滤）
     */
    public RetrievalTrace retrieveWithTrace(String query, String strategy, int topK, double threshold,
                                            Set<Long> kbIds, Set<Long> documentIds,
                                            int rrfK, boolean enableRerank, double rerankThreshold,
                                            List<String> excludeKeywords) {
        boolean needVector = !"bm25".equals(strategy);
        boolean needBm25 = !"vector".equals(strategy);

        List<HybridSearchService.RankedResult> vector = new ArrayList<>();
        List<HybridSearchService.RankedResult> bm25 = new ArrayList<>();
        Long vectorMs = null;
        Long bm25Ms = null;

        if (needVector && needBm25) {
            CompletableFuture<TimedResult> vectorFuture = CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                List<HybridSearchService.RankedResult> r =
                        traceVectorRetrieve(query, topK, kbIds, documentIds, threshold);
                return new TimedResult(r, System.currentTimeMillis() - start);
            });
            CompletableFuture<TimedResult> bm25Future = CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                List<HybridSearchService.RankedResult> r =
                        traceBm25Retrieve(query, topK, kbIds, documentIds, threshold);
                return new TimedResult(r, System.currentTimeMillis() - start);
            });
            TimedResult vr = vectorFuture.join();
            TimedResult br = bm25Future.join();
            vector = vr.value;
            vectorMs = vr.elapsedMs;
            bm25 = br.value;
            bm25Ms = br.elapsedMs;
        } else if (needVector) {
            long start = System.currentTimeMillis();
            vector = traceVectorRetrieve(query, topK, kbIds, documentIds, threshold);
            vectorMs = System.currentTimeMillis() - start;
        } else {
            long start = System.currentTimeMillis();
            bm25 = traceBm25Retrieve(query, topK, kbIds, documentIds, threshold);
            bm25Ms = System.currentTimeMillis() - start;
        }

        List<HybridSearchService.RankedResult> fusion = new ArrayList<>();
        if (needVector && needBm25) {
            fusion = hybridSearchService.fuseRanked(vector, bm25, topK, rrfK);
        }

        // ===== 父上下文扩展（对齐生产阶段1.5）：用入库预计算的 parent_text 替换片段文本 =====
        fusion = parentContextExpander.expand(fusion);

        // ===== 排除关键词过滤（对齐生产阶段2）：分词后精确匹配排除词，避免子串误杀 =====
        fusion = filterByExcludeKeywords(fusion, excludeKeywords);

        List<RetrievalTrace.RerankedResult> rerank = new ArrayList<>();
        Long rerankMs = null;
        if (needVector && needBm25 && enableRerank) {
            long start = System.currentTimeMillis();
            rerank = traceRerank(query, fusion, rerankThreshold);
            rerankMs = System.currentTimeMillis() - start;
        } else if (needVector && needBm25) {
            // ===== content_type 降权（对齐生产阶段5）：Rerank 关闭时，融合结果即最终结果 =====
            fusion = applyContentTypeWeighting(fusion);
        }

        return new RetrievalTrace(vector, bm25, fusion, rerank, vectorMs, bm25Ms, rerankMs);
    }

    private List<HybridSearchService.RankedResult> traceVectorRetrieve(String query, int topK, Set<Long> kbIds,
                                                                       Set<Long> documentIds, double threshold) {
        try {
            return hybridSearchService.vectorSearchRanked(query, topK, kbIds, documentIds).stream()
                    .filter(r -> r.getScore() >= threshold)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("召回测试-向量检索失败: query={}", query, e);
            throw new BusinessException(500, "向量检索失败");
        }
    }

    private List<HybridSearchService.RankedResult> traceBm25Retrieve(String query, int topK, Set<Long> kbIds,
                                                                     Set<Long> documentIds, double threshold) {
        try {
            List<HybridSearchService.RankedResult> ranked =
                    hybridSearchService.keywordSearchRanked(query, topK, kbIds, documentIds);
            if (ranked.isEmpty()) {
                return new ArrayList<>();
            }
            double maxRawScore = ranked.stream()
                    .mapToDouble(HybridSearchService.RankedResult::getScore)
                    .max()
                    .orElse(0);
            if (maxRawScore <= 0) {
                return new ArrayList<>();
            }
            // BM25 归一化到 0~1，便于与向量相似度统一阈值口径
            return ranked.stream()
                    .map(r -> new HybridSearchService.RankedResult(r.getSegment(), r.getScore() / maxRawScore))
                    .filter(r -> r.getScore() >= threshold)
                    .collect(Collectors.toList());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("召回测试-BM25检索失败: query={}", query, e);
            throw new BusinessException(500, "BM25 检索失败");
        }
    }

    private List<RetrievalTrace.RerankedResult> traceRerank(String query, List<HybridSearchService.RankedResult> fusion,
                                                            double rerankThreshold) {
        if (!rerankerService.isAvailable()) {
            log.error("召回测试-Rerank重排序失败: Reranker 不可用");
            throw new BusinessException(500, "Rerank 重排序失败");
        }
        if (fusion.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> docTexts = fusion.stream()
                    .map(r -> r.getSegment().text())
                    .collect(Collectors.toList());
            List<RerankerService.RerankResult> rerankResults =
                    rerankerService.rerank(query, docTexts, docTexts.size());
            List<RetrievalTrace.RerankedResult> items = new ArrayList<>();
            for (RerankerService.RerankResult rr : rerankResults) {
                if (rr.getScore() < rerankThreshold) {
                    continue;
                }
                HybridSearchService.RankedResult base = fusion.get(rr.getIndex());
                items.add(new RetrievalTrace.RerankedResult(base.getSegment(), rr.getScore(), rr.getIndex() + 1));
            }
            // ===== content_type 降权（对齐生产阶段5）：非正文片段（题目/词汇/答案）降权后重排 =====
            return applyContentTypeWeightingToReranked(items);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("召回测试-Rerank重排序失败", e);
            throw new BusinessException(500, "Rerank 重排序失败");
        }
    }

    /** 带耗时的检索结果包装，用于 hybrid 并行检索时的分路计时 */
    private static class TimedResult {
        final List<HybridSearchService.RankedResult> value;
        final long elapsedMs;

        TimedResult(List<HybridSearchService.RankedResult> value, long elapsedMs) {
            this.value = value;
            this.elapsedMs = elapsedMs;
        }
    }

    /**
     * <h3>检索分路观测结果</h3>
     *
     * <p>承载向量 / BM25 / 融合 / 精排四路结果及其耗时，供召回测试映射到响应结构。
     * 各路的 {@code score} 口径：向量=余弦相似度(0~1)，BM25=归一化分数(0~1)，
     * 融合=RRF 累加分数（Rerank 关闭时经 content_type 降权），
     * 精排=Cross-Encoder 原始 logits 经 content_type 降权。</p>
     */
    public static class RetrievalTrace {
        private final List<HybridSearchService.RankedResult> vector;
        private final List<HybridSearchService.RankedResult> bm25;
        private final List<HybridSearchService.RankedResult> fusion;
        private final List<RerankedResult> rerank;
        private final Long vectorMs;
        private final Long bm25Ms;
        private final Long rerankMs;

        public RetrievalTrace(List<HybridSearchService.RankedResult> vector,
                              List<HybridSearchService.RankedResult> bm25,
                              List<HybridSearchService.RankedResult> fusion,
                              List<RerankedResult> rerank,
                              Long vectorMs, Long bm25Ms, Long rerankMs) {
            this.vector = vector;
            this.bm25 = bm25;
            this.fusion = fusion;
            this.rerank = rerank;
            this.vectorMs = vectorMs;
            this.bm25Ms = bm25Ms;
            this.rerankMs = rerankMs;
        }

        public List<HybridSearchService.RankedResult> getVector() { return vector; }
        public List<HybridSearchService.RankedResult> getBm25() { return bm25; }
        public List<HybridSearchService.RankedResult> getFusion() { return fusion; }
        public List<RerankedResult> getRerank() { return rerank; }
        public Long getVectorMs() { return vectorMs; }
        public Long getBm25Ms() { return bm25Ms; }
        public Long getRerankMs() { return rerankMs; }

        /**
         * Rerank 精排结果：保留精排分数与在融合列表中的原始下标（粗排位次）。
         */
        public static class RerankedResult {
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
    }

}