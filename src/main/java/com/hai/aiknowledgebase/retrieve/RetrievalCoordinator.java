package com.hai.aiknowledgebase.retrieve;

import com.hai.aiknowledgebase.annotation.Timed;
import com.hai.aiknowledgebase.config.ThresholdsConfig;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.service.ParamValueProvider;
import com.hai.aiknowledgebase.service.ParentContextExpander;
import com.hai.aiknowledgebase.service.RerankerService;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * <h2>检索协调器</h2>
 *
 * <p>负责编排「混合检索 → 排除词过滤 → Rerank 精排 → 阈值过滤 → content_type 降权 → 父上下文扩展」
 * 的完整链路。父上下文扩展放在精排之后，避免 parent_text 过长被 Reranker 截断。
 * 底层检索原语与事后处理分别委托给独立组件：</p>
 * <ul>
 *   <li>{@link VectorRetriever}：向量语义检索</li>
 *   <li>{@link KeywordRetriever}：BM25 关键词检索</li>
 *   <li>{@link RankFusion}：RRF 融合（生产 / 召回测试两种去重键）</li>
 *   <li>{@link RerankOrchestrator}：精排、阈值过滤、排除词过滤、降权、跨库重排</li>
 *   <li>{@link ChunkIndexManager}：BM25 索引与检索过滤集合的生命周期</li>
 * </ul>
 *
 * <h3>两条编排入口</h3>
 * <ul>
 *   <li>{@link #retrieveSegments}：生产链路（智慧问答）</li>
 *   <li>{@link #retrieveWithTrace}：召回测试链路，额外采集分路结果与耗时</li>
 * </ul>
 * <p>两条入口共用同一套阶段实现（父上下文扩展、排除词过滤、降权、跨库重排），
 * 保证观测结果与生产行为一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalCoordinator {

    /** 向量检索原语 */
    private final VectorRetriever vectorRetriever;

    /** 关键词检索原语 */
    private final KeywordRetriever keywordRetriever;

    /** RRF 融合编排 */
    private final RankFusion rankFusion;

    /** 片段索引管理器，用于判断关键词索引是否为空 */
    private final ChunkIndexManager chunkIndexManager;

    /** Cross-Encoder Rerank 服务，对检索结果精排 */
    private final RerankerService rerankerService;

    /** 父上下文扩展器，用入库时预计算的 parent_text 替换 chunk 文本 */
    private final ParentContextExpander parentContextExpander;

    /** 阈值配置 */
    private final ThresholdsConfig thresholdsConfig;

    /** 参数值读取器，按全局作用域动态读取检索调优参数（rrfK / vectorMinScore 等） */
    private final ParamValueProvider paramValueProvider;

    /** Rerank 编排器：精排 / 阈值过滤 / 排除词过滤 / 降权 / 跨库重排 */
    private final RerankOrchestrator rerankOrchestrator;

    /** 检索扇出执行器（虚拟线程），避免子查询嵌套并行争用 chatTaskExecutor */
    private final ExecutorService retrievalVirtualExecutor;

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
    public List<RankedResult> retrieveSegments(String userQuery, int topK) {
        return retrieveSegments(userQuery, topK, -1, null, null, null);
    }

    /**
     * <h3>检索文档片段（三阶段级联 + 分数重排序）</h3>
     *
     * @param userQuery       用户原始查询（经查询改写后的 rewrittenQuery）
     * @param topK            返回结果数量上限
     * @param minScore        最低相似度阈值（0.0~1.0），≤0 时使用服务端默认值
     * @param expandKeywords  扩展关键词（已废弃，由阶段1混合检索的BM25覆盖）
     * @param excludeKeywords 排除关键词列表
     * @return 按分数降序排列的带分数文档片段列表
     */
    public List<RankedResult> retrieveSegments(String userQuery, int topK,
                                               double minScore,
                                               List<String> expandKeywords,
                                               List<String> excludeKeywords) {
        return retrieveSegments(userQuery, topK, minScore, expandKeywords, excludeKeywords, null);
    }

    @Timed("混合检索+Rerank")
    public List<RankedResult> retrieveSegments(String userQuery, int topK,
                                               double minScore,
                                               List<String> expandKeywords,
                                               List<String> excludeKeywords,
                                               Set<Long> kbIds) {
        // 向量阈值：请求指定优先，其次全局动态参数，最后 yml 兜底
        double effectiveMinScore = (minScore > 0) ? minScore
                : paramValueProvider.getDouble("retrieval.vectorMinScore", getMinVectorScore());
        // 动态参数全部按全局作用域读取（param 优先，yml/硬编码兜底）
        boolean rerankEnabled = paramValueProvider.getBoolean(
                "rerank.enabled", rerankerService.isEnabled());
        boolean parentContextEnabled = paramValueProvider.getBoolean(
                "retrieval.parentContextEnabled", true);
        // 精排候选上限与超时：参数管理可热更，yml 兜底
        int rerankMaxDocs = paramValueProvider.getInt(
                "rerank.maxDocs", rerankerService.getMaxDocs());
        long rerankTimeoutMs = paramValueProvider.getInt(
                "rerank.timeoutMs", (int) rerankerService.getTimeoutMs());
        log.info("开始检索，用户查询: {} | topK: {} | minScore: {} | 扩展词: {} | 排除词: {} | kbIds: {} | rerankEnabled: {} | parentContextEnabled: {} | rerankMaxDocs: {} | rerankTimeoutMs: {}",
                userQuery, topK, effectiveMinScore, expandKeywords, excludeKeywords, kbIds, rerankEnabled, parentContextEnabled, rerankMaxDocs, rerankTimeoutMs);

        // ===== 阶段1：混合检索（主路径）=====
        // 参数已全局化，多库直接一次混合检索，不再按参数指纹拆库分组
        List<RankedResult> allResults = new ArrayList<>();
        if (kbIds == null || !kbIds.isEmpty()) {
            int rrfK = paramValueProvider.getInt("retrieval.rrfK", rankFusion.getDefaultRrfK());
            allResults = new ArrayList<>(
                    hybridSearchRanked(userQuery, topK, effectiveMinScore, kbIds, rrfK));
        }

        log.info("混合检索召回 {} 个片段", allResults.size());

        // ===== 阶段2~6：排除词过滤 → Rerank → 阈值 → 降权 → 父上下文扩展 =====
        // 抽为统一后处理，供生产单查询链路与多子查询统一精排链路复用，保证行为一致
        return applyPostProcessing(allResults, userQuery, excludeKeywords);
    }

    /**
     * <h3>统一后处理</h3>
     *
     * <p>顺序固定为：排除关键词过滤 → Cross-Encoder Rerank → 最终重排序 →
     * 分数阈值过滤 → content_type 降权 → 父上下文扩展。</p>
     *
     * <p>父上下文扩展放在最后，避免 parent_text 过长被 Reranker 的 max-seq-length 截断，
     * 同时只扩展最终结果，减少 token 与耗时。</p>
     *
     * @param allResults      候选片段（混合检索或跨子查询合并结果）
     * @param query           用于精排的查询文本
     * @param excludeKeywords 排除关键词
     * @return 后处理后的最终结果
     */
    private List<RankedResult> applyPostProcessing(List<RankedResult> allResults, String query,
                                                   List<String> excludeKeywords) {
        boolean rerankEnabled = paramValueProvider.getBoolean(
                "rerank.enabled", rerankerService.isEnabled());
        double rerankMinScore = paramValueProvider.getDouble(
                "rerank.minScore", rerankerService.getMinScore());
        boolean parentContextEnabled = paramValueProvider.getBoolean(
                "retrieval.parentContextEnabled", true);
        int rerankMaxDocs = paramValueProvider.getInt(
                "rerank.maxDocs", rerankerService.getMaxDocs());
        int rerankCandidateCount = paramValueProvider.getInt("rerank.candidateCount", 0);
        long rerankTimeoutMs = paramValueProvider.getInt(
                "rerank.timeoutMs", (int) rerankerService.getTimeoutMs());

        // ===== 阶段2：排除关键词过滤 =====
        // 对片段分词后精确匹配排除词（避免子串误杀，如排除"Java"误杀"JavaScript"）
        allResults = rerankOrchestrator.filterByExcludeKeywords(allResults, excludeKeywords);

        // ===== 阶段3：Cross-Encoder Rerank 精排 =====
        boolean rerankApplied = false;
        if (rerankEnabled && rerankerService.isAvailable() && !allResults.isEmpty()) {
            rerankApplied = true;
            allResults = limitRerankCandidates(allResults,
                    resolveRerankCandidateLimit(rerankCandidateCount, rerankMaxDocs), "生产检索");
            log.info("开始 Rerank 精排，候选片段数: {}", allResults.size());

            allResults = rerankOrchestrator.rerank(query, allResults, rerankMaxDocs, rerankTimeoutMs);

            log.info("Rerank 精排完成，Top-3 分数: {}", top3Scores(allResults));
        } else if (rerankEnabled && !rerankerService.isAvailable()) {
            log.warn("rerank.enabled 已开启但 Reranker 模型未加载（yml reranker.enabled=false 或初始化失败），跳过精排");
        }

        // ===== 最终重排序 =====
        // - Rerank 开启：score 已为 Cross-Encoder 精排分，直接按精排分降序。
        // - Rerank 关闭：跨库合并后绝不能用 RRF 分做最终排序，改按原始向量相似度
        //   （vectorScore）降序，无向量分则回退 BM25 分（bm25Score）；并把 score 重指为
        //   实际排序值，否则后续降权内部重新 sort 会再次用 RRF 分破坏顺序。
        if (rerankApplied) {
            allResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        } else {
            allResults = rerankOrchestrator.reorderByRawScore(allResults);
        }
        log.info("分数重排序完成，Top-3 分数: {}", top3Scores(allResults));

        // ===== 阶段4：分数阈值过滤 =====
        allResults = rerankOrchestrator.filterByMinScore(allResults, rerankMinScore);

        // ===== 阶段5：content_type 降权 =====
        // 对非正文内容（题目、词汇表、答案）降低分数，确保正文优先
        allResults = rerankOrchestrator.applyContentTypeWeighting(allResults, null);

        // ===== 阶段6：父上下文扩展 =====
        if (parentContextEnabled) {
            allResults = parentContextExpander.expand(allResults);
        } else {
            log.debug("父上下文扩展已关闭（retrieval.parentContextEnabled=false），跳过 parent_text 替换");
        }

        log.info("最终返回 {} 个片段", allResults.size());
        return allResults;
    }

    private String top3Scores(List<RankedResult> results) {
        return results.stream().limit(3)
                .map(r -> String.format("%.4f", r.getScore()))
                .collect(Collectors.joining(", "));
    }

    /**
     * 计算实际送入精排的候选上限：{@code rerank.candidateCount} 与 {@code rerank.maxDocs} 取小。
     *
     * <p>{@code candidateCount ≤ 0} 表示不额外限制，仅受 maxDocs 约束；
     * 两者都无效时返回 {@link Integer#MAX_VALUE}，等价于不截断。</p>
     */
    private int resolveRerankCandidateLimit(int candidateCount, int maxDocs) {
        if (candidateCount <= 0) {
            return maxDocs > 0 ? maxDocs : Integer.MAX_VALUE;
        }
        return maxDocs > 0 ? Math.min(candidateCount, maxDocs) : candidateCount;
    }

    /**
     * 按粗排顺序截断精排候选，仅影响送入 Cross-Encoder 的数量，不改变召回结果本身。
     */
    private List<RankedResult> limitRerankCandidates(List<RankedResult> candidates, int limit,
                                                     String stage) {
        if (limit <= 0 || candidates.size() <= limit) {
            return candidates;
        }
        log.info("精排候选截断[{}]: {} -> {}（rerank.candidateCount 与 rerank.maxDocs 取小）",
                stage, candidates.size(), limit);
        return new ArrayList<>(candidates.subList(0, limit));
    }

    // ==================== 多子查询检索（合并后统一精排） ====================

    /**
     * <h3>多子查询检索（合并去重后统一 Rerank）</h3>
     *
     * <p>与 {@link #retrieveSegments(String, int, double, List, List, Set)} 的差异：
     * 每个子查询只执行「混合检索 + RRF 融合」，合并去重后统一执行一次排除词过滤、Rerank、
     * 阈值过滤、降权与父上下文扩展。</p>
     *
     * <p>动机：Rerank 由单线程执行器串行处理，旧实现每子查询各精排一次，
     * 延迟随子查询数线性放大，且每个 batch 过小导致 ONNX 固定开销占比过高。</p>
     *
     * <p>回滚：参数 {@code retrieval.multiQuerySingleRerank=false} 时回退旧行为
     * （每子查询各自完整链路）。</p>
     *
     * @param rerankQuery     主查询，作为统一精排的 query
     * @param subQueries      子查询列表
     * @param totalTopK       最终返回上限（各子查询按 totalTopK/N 分配召回配额）
     * @param minScore        向量最低相似度，≤0 时使用参数管理值
     * @param expandKeywords  扩展关键词（已废弃，仅保持签名一致）
     * @param excludeKeywords 排除关键词
     * @param kbIds           知识库范围
     * @return 合并去重并统一精排后的结果
     */
    @Timed("多子查询检索+Rerank")
    public List<RankedResult> retrieveSegmentsMultiQuery(String rerankQuery, List<String> subQueries,
                                                         int totalTopK, double minScore,
                                                         List<String> expandKeywords,
                                                         List<String> excludeKeywords,
                                                         Set<Long> kbIds) {
        if (kbIds != null && kbIds.isEmpty()) {
            return new ArrayList<>();
        }
        if (subQueries == null || subQueries.isEmpty()) {
            return retrieveSegments(rerankQuery, totalTopK, minScore, expandKeywords, excludeKeywords, kbIds);
        }

        double effectiveMinScore = (minScore > 0) ? minScore
                : paramValueProvider.getDouble("retrieval.vectorMinScore", getMinVectorScore());
        boolean singleRerank = paramValueProvider.getBoolean(
                "retrieval.multiQuerySingleRerank", true);
        if (!singleRerank) {
            log.info("multiQuerySingleRerank=false，回退为每子查询完整链路（旧行为）");
            return legacyMultiQueryRetrieve(subQueries, totalTopK, effectiveMinScore,
                    expandKeywords, excludeKeywords, kbIds);
        }

        int perQueryTopK = Math.max(3, totalTopK / subQueries.size());
        int rrfK = paramValueProvider.getInt("retrieval.rrfK", rankFusion.getDefaultRrfK());
        log.info("多子查询统一精排: {} 个子查询 | perQueryTopK={} | 精排query={}",
                subQueries.size(), perQueryTopK, rerankQuery);

        // ===== 阶段1：每子查询只做混合检索 + RRF（不精排、不做后处理）=====
        List<CompletableFuture<TimedResult>> futures = new ArrayList<>(subQueries.size());
        for (String subQuery : subQueries) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                List<RankedResult> results =
                        hybridSearchInline(subQuery, perQueryTopK, effectiveMinScore, kbIds, rrfK);
                return new TimedResult(results, System.currentTimeMillis() - start);
            }, retrievalVirtualExecutor));
        }

        // 合并去重：同一片段被多个子查询命中时保留分数更高的一份
        // 同时记录各子查询的召回键集合与耗时，供分路归因（不影响检索行为）
        Map<String, RankedResult> merged = new LinkedHashMap<>();
        List<Set<String>> recalledKeys = new ArrayList<>(subQueries.size());
        List<Long> recallMs = new ArrayList<>(subQueries.size());
        for (int i = 0; i < futures.size(); i++) {
            Set<String> keys = new HashSet<>();
            long elapsed = -1L;
            try {
                TimedResult timed = futures.get(i).join();
                elapsed = timed.elapsedMs;
                for (RankedResult r : timed.value) {
                    keys.add(attributionKey(r));
                    merged.merge(segmentKey(r), r,
                            (existing, incoming) -> incoming.getScore() > existing.getScore() ? incoming : existing);
                }
            } catch (Exception e) {
                log.warn("子查询检索失败，跳过: subQuery={}, error={}", subQueries.get(i), e.getMessage());
            }
            recalledKeys.add(keys);
            recallMs.add(elapsed);
        }

        List<RankedResult> candidates = new ArrayList<>(merged.values());
        candidates.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        log.info("子查询合并去重后候选数: {}", candidates.size());

        List<RankedResult> finalResults = applyPostProcessing(candidates, rerankQuery, excludeKeywords);
        logSubQueryAttribution(subQueries, recalledKeys, recallMs, finalResults, candidates.size());
        return finalResults;
    }

    /**
     * 分路归因日志：每个子查询召回了多少、其中多少是它独有的、耗时多少、
     * 最终结果里有多少条来自它（含独占贡献）。
     *
     * <p>仅用于观测，帮助判断"子查询是否真的有贡献"，不参与任何排序或过滤。</p>
     */
    private void logSubQueryAttribution(List<String> subQueries, List<Set<String>> recalledKeys,
                                        List<Long> recallMs, List<RankedResult> finalResults,
                                        int candidateCount) {
        // key → 召回该片段的子查询下标集合
        Map<String, Set<Integer>> keyOwners = new HashMap<>();
        for (int i = 0; i < recalledKeys.size(); i++) {
            for (String key : recalledKeys.get(i)) {
                keyOwners.computeIfAbsent(key, k -> new HashSet<>()).add(i);
            }
        }

        int[] contributed = new int[subQueries.size()];
        int[] exclusiveContributed = new int[subQueries.size()];
        int unattributed = 0;
        for (RankedResult r : finalResults) {
            Set<Integer> owners = keyOwners.get(attributionKey(r));
            if (owners == null || owners.isEmpty()) {
                unattributed++;
                continue;
            }
            for (int owner : owners) {
                contributed[owner]++;
                if (owners.size() == 1) {
                    exclusiveContributed[owner]++;
                }
            }
        }

        for (int i = 0; i < subQueries.size(); i++) {
            int exclusiveRecall = 0;
            for (String key : recalledKeys.get(i)) {
                Set<Integer> owners = keyOwners.get(key);
                if (owners != null && owners.size() == 1) {
                    exclusiveRecall++;
                }
            }
            log.info("子查询分路归因: idx={} | 召回={} | 独占召回={} | 检索耗时={}ms | 最终贡献={} | 独占贡献={} | subQuery={}",
                    i + 1, recalledKeys.get(i).size(), exclusiveRecall, recallMs.get(i),
                    contributed[i], exclusiveContributed[i], subQueries.get(i));
        }
        log.info("子查询归因汇总: 子查询数={} | 合并候选={} | 最终返回={} | 无法归因={}",
                subQueries.size(), candidateCount, finalResults.size(), unattributed);
    }

    /**
     * 归因键：优先 chunk_id（父上下文扩展会替换文本但保留 metadata），
     * 缺失时回退到文本键，保证扩展前后仍可对齐。
     */
    private String attributionKey(RankedResult result) {
        if (result.getSegment() != null) {
            String chunkId = chunkKey(result.getSegment());
            if (chunkId != null) {
                return "chunk:" + chunkId;
            }
        }
        return "text:" + segmentKey(result);
    }

    /**
     * 回滚路径：每子查询各自执行完整链路（混合检索 + 精排 + 后处理），再合并去重。
     */
    private List<RankedResult> legacyMultiQueryRetrieve(List<String> subQueries, int totalTopK,
                                                        double minScore, List<String> expandKeywords,
                                                        List<String> excludeKeywords, Set<Long> kbIds) {
        int perQueryTopK = Math.max(3, totalTopK / subQueries.size());
        List<CompletableFuture<List<RankedResult>>> futures = new ArrayList<>(subQueries.size());
        for (String subQuery : subQueries) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> retrieveSegments(subQuery, perQueryTopK, minScore, expandKeywords, excludeKeywords, kbIds),
                    retrievalVirtualExecutor));
        }

        Map<String, RankedResult> merged = new LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                for (RankedResult r : futures.get(i).join()) {
                    merged.putIfAbsent(segmentKey(r), r);
                }
            } catch (Exception e) {
                log.warn("子查询检索失败，跳过: subQuery={}, error={}", subQueries.get(i), e.getMessage());
            }
        }

        List<RankedResult> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return result;
    }

    /**
     * 混合检索（当前线程内顺序执行向量路与 BM25 路）。
     *
     * <p>供多子查询路径使用：子查询之间已经并行，内部再嵌套异步会争用线程池；
     * 两路顺序执行仅增加一次 ANN/BM25 的耗时，避免嵌套并行带来的饥饿风险。</p>
     */
    private List<RankedResult> hybridSearchInline(String query, int topK, double minVectorScore,
                                                  Set<Long> kbIds, int rrfK) {
        boolean hybridEnabled = paramValueProvider.getBoolean(
                "retrieval.hybridEnabled", rankFusion.isHybridEnabled());
        if (!hybridEnabled || chunkIndexManager.getKeywordIndex().size() == 0) {
            return vectorRetriever.vectorSearchRanked(query, topK, kbIds).stream()
                    .filter(r -> r.getScore() >= minVectorScore)
                    .collect(Collectors.toList());
        }

        List<RankedResult> vectorResults = vectorRetriever.vectorSearchRanked(query, topK, kbIds).stream()
                .filter(r -> r.getScore() >= minVectorScore)
                .collect(Collectors.toList());
        List<RankedResult> keywordResults = keywordRetriever.keywordSearchRanked(query, topK, kbIds);
        return rankFusion.reciprocalRankFusion(vectorResults, keywordResults, topK, rrfK);
    }

    /** 去重键：优先片段文本；文本缺失时退化为对象标识，避免不同片段被误合并 */
    private String segmentKey(RankedResult result) {
        String text = (result.getSegment() == null) ? null : result.getSegment().text();
        return (text != null) ? text : String.valueOf(System.identityHashCode(result));
    }

    /**
     * <h3>混合检索（带向量相关性阈值，返回带分数结果）</h3>
     *
     * <p>默认使用 yml 配置的 RRF 平滑系数，供评测等不拆库场景使用。</p>
     */
    public List<RankedResult> hybridSearchRanked(String query, int topK, double minVectorScore) {
        return hybridSearchRanked(query, topK, minVectorScore, null, rankFusion.getDefaultRrfK());
    }

    /**
     * <h3>混合检索（带向量相关性阈值，支持显式 RRF 平滑系数）</h3>
     *
     * <p>显式传入 {@code rrfK}，由调用方按参数管理值解析后传入。</p>
     *
     * <p>并行发起向量路径与关键词路径检索；混合检索关闭或关键词索引为空时降级为纯向量检索。
     * 混合检索开关支持参数管理热更，yml 值仅作兜底。</p>
     *
     * @param query          用户查询文本
     * @param topK           返回结果数量上限
     * @param minVectorScore 向量路径最低余弦相似度阈值（0.0~1.0）
     * @param kbIds          知识库范围（null 表示不过滤）
     * @param rrfK           RRF 平滑系数
     * @return 按 RRF 融合分数降序排列的 RankedResult 列表
     */
    public List<RankedResult> hybridSearchRanked(String query, int topK, double minVectorScore,
                                                 Set<Long> kbIds, int rrfK) {
        // 降级判断：如果混合检索关闭或关键词索引为空，直接走纯向量检索
        // 开关按参数管理热更，yml 值仅作兜底
        boolean hybridEnabled = paramValueProvider.getBoolean(
                "retrieval.hybridEnabled", rankFusion.isHybridEnabled());
        if (!hybridEnabled || chunkIndexManager.getKeywordIndex().size() == 0) {
            log.info("混合检索已关闭（hybridEnabled={}）或关键词索引为空，降级为纯向量检索", hybridEnabled);
            return vectorRetriever.vectorSearchRanked(query, topK, kbIds).stream()
                    .filter(r -> r.getScore() >= minVectorScore)
                    .collect(Collectors.toList());
        }

        // 并行发起两路检索：向量路径 + 关键词路径
        CompletableFuture<List<RankedResult>> vectorFuture =
                CompletableFuture.supplyAsync(() -> vectorRetriever.vectorSearchRanked(query, topK, kbIds));
        CompletableFuture<List<RankedResult>> keywordFuture =
                CompletableFuture.supplyAsync(() -> keywordRetriever.keywordSearchRanked(query, topK, kbIds));

        List<RankedResult> vectorResults = vectorFuture.join().stream()
                .filter(r -> r.getScore() >= minVectorScore)
                .collect(Collectors.toList());
        List<RankedResult> keywordResults = keywordFuture.join();

        log.info("双路检索完成: 向量结果={} (过滤后), 关键词结果={}", vectorResults.size(), keywordResults.size());

        // RRF 融合排序
        List<RankedResult> fused = rankFusion.reciprocalRankFusion(vectorResults, keywordResults, topK, rrfK);
        log.info("RRF 融合结果数量: {}", fused.size());

        return fused;
    }

    // ==================== 分路观测检索（召回测试复用） ====================

    /**
     * <h3>带分路观测的检索（供召回测试复用）</h3>
     *
     * <p>将向量 / BM25 单路检索、RRF 融合、Rerank 精排统一编排在本服务内，
     * 复用检索原语与精排能力，避免召回测试独立实现重复逻辑。</p>
     *
     * <p>hybrid 的融合结果依次经过排除关键词过滤、精排（可选）、阈值过滤、content_type 降权，
     * 最后再做父上下文扩展（parent_text），保证 Reranker 看到的是原始命中 chunk。
     * 这样召回测试观测到的检索行为与智慧问答生产链路一致。开启 Rerank 时，
     * 该精排层同样可挂载在 vector / bm25 单路结果之后。</p>
     *
     * @param query           查询文本
     * @param strategy        检索策略：hybrid / vector / bm25
     * @param topK            每路返回数量上限
     * @param threshold       相似度阈值（0~1）：向量按余弦相似度过滤，BM25 按归一化分数过滤
     * @param kbIds           知识库范围（null 表示不过滤）
     * @param documentIds     文档范围（null/空 表示不过滤）
     * @param rrfK            RRF 平滑系数
     * @param enableRerank    是否启用 Rerank 精排（hybrid / vector / bm25 均可挂载）
     * @param rerankThreshold Rerank 分数阈值（Cross-Encoder 原始 logits）
     * @param excludeKeywords 排除关键词列表（null/空 表示不过滤）
     * @param candidateMultiplier 候选池倍数（开启 Rerank 时生效）
     * @param maxCandidates       候选池上限（开启 Rerank 时生效）
     * @param topN                最终返回数量（开启 Rerank 时在精排过滤后截取）
     * @param rerankStage         Rerank 挂载阶段：fuse_rerank（默认）/ rerank_fuse（实验）
     * @param rerankMaxDocsOverride 本次测试的精排候选上限覆盖（≤0 表示使用参数管理 rerank.maxDocs）
     */
    public RetrievalTrace retrieveWithTrace(String query, String strategy, int topK, double threshold,
                                            Set<Long> kbIds, Set<Long> documentIds,
                                            int rrfK, boolean enableRerank, double rerankThreshold,
                                            List<String> excludeKeywords,
                                            int candidateMultiplier, int maxCandidates, int topN,
                                            String rerankStage, int rerankMaxDocsOverride) {
        boolean needVector = !"bm25".equals(strategy);
        boolean needBm25 = !"vector".equals(strategy);
        // 候选池：开启 Rerank 时先扩大召回，精排后再截取 topN；未开启时保持原 topK 行为
        int retrieveTopK = enableRerank
                ? Math.max(topK, Math.min(maxCandidates, topK * Math.max(1, candidateMultiplier)))
                : topK;
        // rerank_fuse：两路各自精排后再 RRF（仅 hybrid 实验模式，默认 fuse_rerank）
        boolean rerankFuse = enableRerank && needVector && needBm25 && "rerank_fuse".equals(rerankStage);
        // 动态参数全部按全局作用域读取；rerankMaxDocs 支持请求级覆盖（仅召回测试）
        boolean parentContextEnabled = paramValueProvider.getBoolean(
                "retrieval.parentContextEnabled", true);
        // 精排候选上限与超时：请求覆盖 > 全局参数 > yml 兜底
        int rerankMaxDocs = rerankMaxDocsOverride > 0
                ? rerankMaxDocsOverride
                : paramValueProvider.getInt("rerank.maxDocs", rerankerService.getMaxDocs());
        int rerankCandidateCount = paramValueProvider.getInt("rerank.candidateCount", 0);
        long rerankTimeoutMs = paramValueProvider.getInt(
                "rerank.timeoutMs", (int) rerankerService.getTimeoutMs());

        List<RankedResult> vector = new ArrayList<>();
        List<RankedResult> bm25 = new ArrayList<>();
        Long vectorMs = null;
        Long bm25Ms = null;

        if (needVector && needBm25) {
            CompletableFuture<TimedResult> vectorFuture = CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                List<RankedResult> r =
                        traceVectorRetrieve(query, retrieveTopK, kbIds, documentIds, threshold);
                return new TimedResult(r, System.currentTimeMillis() - start);
            });
            CompletableFuture<TimedResult> bm25Future = CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                List<RankedResult> r =
                        traceBm25Retrieve(query, retrieveTopK, kbIds, documentIds, threshold);
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
            vector = traceVectorRetrieve(query, retrieveTopK, kbIds, documentIds, threshold);
            vectorMs = System.currentTimeMillis() - start;
        } else {
            long start = System.currentTimeMillis();
            bm25 = traceBm25Retrieve(query, retrieveTopK, kbIds, documentIds, threshold);
            bm25Ms = System.currentTimeMillis() - start;
        }

        List<RankedResult> fusion = new ArrayList<>();
        if (!rerankFuse) {
            if (needVector && needBm25) {
                fusion = rankFusion.fuseRanked(vector, bm25, retrieveTopK, rrfK);
            }

            // ===== 排除关键词过滤（对齐生产阶段2）：分词后精确匹配排除词，避免子串误杀 =====
            fusion = rerankOrchestrator.filterByExcludeKeywords(fusion, excludeKeywords);
        }

        List<RerankedResult> rerank = new ArrayList<>();
        Long rerankMs = null;
        boolean rerankApplied = false;
        if (rerankFuse) {
            // 实验模式：两路各自精排（先做排除词过滤），再 RRF 融合，最后降权与父上下文扩展
            long start = System.currentTimeMillis();
            // 精排阶段使用原始 chunk；父上下文扩展统一放到精排 / 阈值过滤之后
            List<RankedResult> vectorCandidates =
                    rerankOrchestrator.filterByExcludeKeywords(vector, excludeKeywords);
            List<RankedResult> bm25Candidates =
                    rerankOrchestrator.filterByExcludeKeywords(bm25, excludeKeywords);
            int candidateLimit = resolveRerankCandidateLimit(rerankCandidateCount, rerankMaxDocs);
            vectorCandidates = limitRerankCandidates(vectorCandidates, candidateLimit, "rerank_fuse-向量路");
            bm25Candidates = limitRerankCandidates(bm25Candidates, candidateLimit, "rerank_fuse-BM25路");
            List<RankedResult> vectorReranked = traceRerankToRanked(
                    query, vectorCandidates, rerankThreshold, rerankMaxDocs, rerankTimeoutMs);
            List<RankedResult> bm25Reranked = traceRerankToRanked(
                    query, bm25Candidates, rerankThreshold, rerankMaxDocs, rerankTimeoutMs);
            fusion = rerankOrchestrator.applyContentTypeWeighting(
                    rankFusion.fuseRanked(vectorReranked, bm25Reranked, retrieveTopK, rrfK), null);
            // ===== 父上下文扩展：放在精排 / 融合 / 降权之后，只扩展最终结果 =====
            if (parentContextEnabled) {
                fusion = parentContextExpander.expand(fusion);
            }
            rerank = toRerankedResults(fusion, vectorReranked, bm25Reranked);
            rerankMs = System.currentTimeMillis() - start;
            rerankApplied = !(vectorReranked.isEmpty() && bm25Reranked.isEmpty());
            // rerank_fuse 下：fusion（最终 RRF 结果）按 topN 截取，rerank 列表保持完整供观测
            if (fusion.size() > topN) {
                fusion = new ArrayList<>(fusion.subList(0, Math.max(1, topN)));
            }
        } else if (enableRerank) {
            // 通用精排层：hybrid 精排 RRF 融合结果，vector / bm25 精排各自单路结果。
            // 其余后处理阶段（父上下文扩展 / 排除词过滤 / content_type 降权）仍仅 hybrid 生效。
            List<RankedResult> rerankInput;
            boolean applyWeighting;
            if (needVector && needBm25) {
                rerankInput = fusion;
                applyWeighting = true;
            } else if (needVector) {
                rerankInput = vector;
                applyWeighting = false;
            } else {
                rerankInput = bm25;
                applyWeighting = false;
            }
            rerankInput = limitRerankCandidates(rerankInput,
                    resolveRerankCandidateLimit(rerankCandidateCount, rerankMaxDocs), "召回测试");
            long start = System.currentTimeMillis();
            rerank = traceRerank(query, rerankInput, rerankThreshold, applyWeighting,
                    rerankMaxDocs, rerankTimeoutMs);
            rerankMs = System.currentTimeMillis() - start;
            rerankApplied = !rerankInput.isEmpty();
            // ===== 父上下文扩展：hybrid 才生效，放在精排 / 阈值过滤 / 降权之后 =====
            if (needVector && needBm25 && parentContextEnabled) {
                rerank = parentContextExpander.expandReranked(rerank);
            }
            // rerank 列表保留完整精排结果（经阈值过滤），topN 截断仅作用于 final 列表（RecallTestService 侧）。
            // 这样前端可以在 rerank Tab 看到完整的精排排序分布，而主表 final 只展示 topN。
        } else if (needVector && needBm25) {
            // ===== 跨库重排（对齐生产最终重排序）：Rerank 关闭时按原始向量相似度排序，避免用 RRF 分混排 =====
            fusion = rerankOrchestrator.reorderByRawScore(fusion);
            // ===== content_type 降权（对齐生产阶段5）：Rerank 关闭时，融合结果即最终结果 =====
            fusion = rerankOrchestrator.applyContentTypeWeighting(fusion, null);
            // ===== 父上下文扩展：放在重排序 / 降权之后，只扩展最终结果 =====
            if (parentContextEnabled) {
                fusion = parentContextExpander.expand(fusion);
            }
        }

        return new RetrievalTrace(vector, bm25, fusion, rerank, vectorMs, bm25Ms, rerankMs,
                rerankApplied, rerankMaxDocs);
    }

    private List<RankedResult> traceVectorRetrieve(String query, int topK, Set<Long> kbIds,
                                                   Set<Long> documentIds, double threshold) {
        try {
            return vectorRetriever.vectorSearchRanked(query, topK, kbIds, documentIds).stream()
                    .filter(r -> r.getScore() >= threshold)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("召回测试-向量检索失败: query={}", query, e);
            throw new BusinessException(500, "向量检索失败");
        }
    }

    private List<RankedResult> traceBm25Retrieve(String query, int topK, Set<Long> kbIds,
                                                 Set<Long> documentIds, double threshold) {
        try {
            List<RankedResult> ranked =
                    keywordRetriever.keywordSearchRanked(query, topK, kbIds, documentIds);
            if (ranked.isEmpty()) {
                return new ArrayList<>();
            }
            double maxRawScore = ranked.stream()
                    .mapToDouble(RankedResult::getScore)
                    .max()
                    .orElse(0);
            if (maxRawScore <= 0) {
                return new ArrayList<>();
            }
            // BM25 归一化到 0~1，便于与向量相似度统一阈值口径
            return ranked.stream()
                    .map(r -> new RankedResult(r.getSegment(), r.getScore() / maxRawScore))
                    .filter(r -> r.getScore() >= threshold)
                    .collect(Collectors.toList());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("召回测试-BM25检索失败: query={}", query, e);
            throw new BusinessException(500, "BM25 检索失败");
        }
    }

    private List<RerankedResult> traceRerank(String query, List<RankedResult> candidates,
                                             double rerankThreshold,
                                             boolean applyContentTypeWeighting,
                                             int maxDocs, long timeoutMs) {
        if (!rerankerService.isAvailable()) {
            log.error("召回测试-Rerank重排序失败: Reranker 不可用");
            throw new BusinessException(500, "Rerank 重排序失败");
        }
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> docTexts = candidates.stream()
                    .map(r -> r.getSegment().text())
                    .collect(Collectors.toList());
            List<RerankerService.RerankResult> rerankResults =
                    rerankerService.rerank(query, docTexts, docTexts.size(), maxDocs, timeoutMs);
            List<RerankedResult> items = new ArrayList<>();
            for (RerankerService.RerankResult rr : rerankResults) {
                if (rr.getScore() < rerankThreshold) {
                    continue;
                }
                RankedResult base = candidates.get(rr.getIndex());
                items.add(new RerankedResult(base.getSegment(), rr.getScore(), rr.getIndex() + 1));
            }
            if (!applyContentTypeWeighting) {
                return items;
            }
            // ===== content_type 降权（对齐生产阶段5）：非正文片段（题目/词汇/答案）降权后重排 =====
            return rerankOrchestrator.applyContentTypeWeightingToReranked(items, null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("召回测试-Rerank重排序失败", e);
            throw new BusinessException(500, "Rerank 重排序失败");
        }
    }

    /**
     * 精排单路候选并返回带精排分的结果列表（供 rerank_fuse 实验模式做二次 RRF）。
     */
    private List<RankedResult> traceRerankToRanked(String query, List<RankedResult> candidates,
                                                   double rerankThreshold,
                                                   int maxDocs, long timeoutMs) {
        if (!rerankerService.isAvailable()) {
            log.error("召回测试-Rerank重排序失败: Reranker 不可用");
            throw new BusinessException(500, "Rerank 重排序失败");
        }
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> docTexts = candidates.stream()
                    .map(r -> r.getSegment().text())
                    .collect(Collectors.toList());
            List<RerankerService.RerankResult> rerankResults =
                    rerankerService.rerank(query, docTexts, docTexts.size(), maxDocs, timeoutMs);
            List<RankedResult> reranked = new ArrayList<>();
            for (RerankerService.RerankResult rr : rerankResults) {
                if (rr.getScore() < rerankThreshold) {
                    continue;
                }
                reranked.add(new RankedResult(candidates.get(rr.getIndex()).getSegment(), rr.getScore()));
            }
            return reranked;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("召回测试-Rerank重排序失败", e);
            throw new BusinessException(500, "Rerank 重排序失败");
        }
    }

    /**
     * 将 rerank_fuse 的 RRF 融合结果映射为精排结果视图：
     * rerankScore 取该片段在所属单路中的精排原始分（两路都命中时取较大值），coarseRank 为融合位次。
     */
    private List<RerankedResult> toRerankedResults(List<RankedResult> fused,
                                                   List<RankedResult> vectorReranked,
                                                   List<RankedResult> bm25Reranked) {
        Map<String, Double> logits = new HashMap<>();
        for (RankedResult r : vectorReranked) {
            putLogits(logits, r);
        }
        for (RankedResult r : bm25Reranked) {
            putLogits(logits, r);
        }
        List<RerankedResult> items = new ArrayList<>();
        for (int i = 0; i < fused.size(); i++) {
            RankedResult r = fused.get(i);
            String key = chunkKey(r.getSegment());
            double score = (key != null && logits.containsKey(key)) ? logits.get(key) : r.getScore();
            items.add(new RerankedResult(r.getSegment(), score, i + 1));
        }
        return items;
    }

    private void putLogits(Map<String, Double> logits, RankedResult result) {
        String key = chunkKey(result.getSegment());
        if (key != null) {
            logits.merge(key, result.getScore(), Math::max);
        }
    }

    /** 读取片段主键作为跨路对齐键，缺失时返回 null */
    private String chunkKey(TextSegment segment) {
        String value = segment.metadata().getString("chunk_id");
        return (value == null || value.isBlank()) ? null : value;
    }

    /** 带耗时的检索结果包装，用于 hybrid 并行检索时的分路计时 */
    private static class TimedResult {
        final List<RankedResult> value;
        final long elapsedMs;

        TimedResult(List<RankedResult> value, long elapsedMs) {
            this.value = value;
            this.elapsedMs = elapsedMs;
        }
    }
}
