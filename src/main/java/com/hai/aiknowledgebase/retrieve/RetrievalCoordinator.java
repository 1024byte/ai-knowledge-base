package com.hai.aiknowledgebase.retrieve;

import com.hai.aiknowledgebase.annotation.Timed;
import com.hai.aiknowledgebase.config.ThresholdsConfig;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.service.ParamService;
import com.hai.aiknowledgebase.service.ParamValueProvider;
import com.hai.aiknowledgebase.service.ParentContextExpander;
import com.hai.aiknowledgebase.service.RerankerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * <h2>检索协调器</h2>
 *
 * <p>负责编排「混合检索 → 父上下文扩展 → 排除词过滤 → Rerank 精排 → 阈值过滤 → content_type 降权」
 * 的完整链路。底层检索原语与事后处理分别委托给独立组件：</p>
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

    /** 参数管理服务，用于按知识库计算参数指纹（多库检索时拆库分组） */
    private final ParamService paramService;

    /** 参数值读取器，按作用域动态读取检索调优参数（rrfK / vectorMinScore 等） */
    private final ParamValueProvider paramValueProvider;

    /** Rerank 编排器：精排 / 阈值过滤 / 排除词过滤 / 降权 / 跨库重排 */
    private final RerankOrchestrator rerankOrchestrator;

    /** 向量检索最低余弦相似度阈值，由 ThresholdsConfig 统一管理 */
    private double getMinVectorScore() {
        return thresholdsConfig.getRetrieval().getVectorMinScore();
    }

    /**
     * 选取本轮检索的代表知识库 ID（方案 A）。
     *
     * <p>多库检索时，rerank.enabled / rerank.minScore / contentType.*.weight /
     * retrieval.parentContextEnabled 这类「全局倾向」参数以代表 kbId 读取：
     * 优先取该知识库的 KB 级覆盖，无则回退全局。代表值取集合最小值以保证确定性。</p>
     *
     * @param kbIds 检索范围（null/空 表示全局）
     * @return 代表 kbId；全局检索时返回 null
     */
    private Long representativeKbId(Set<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return null;
        }
        return Collections.min(kbIds);
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
        double effectiveMinScore = (minScore > 0) ? minScore : getMinVectorScore();
        // 多库检索的代表 kbId（方案 A）：全局倾向参数按代表库读取，无覆盖则回退全局
        Long repKbId = representativeKbId(kbIds);
        // 动态参数（param 优先，yml/硬编码兜底）
        boolean rerankEnabled = paramValueProvider.getBoolean(
                "rerank.enabled", repKbId, rerankerService.isEnabled());
        double rerankMinScore = paramValueProvider.getDouble(
                "rerank.minScore", repKbId, rerankerService.getMinScore());
        boolean parentContextEnabled = paramValueProvider.getBoolean(
                "retrieval.parentContextEnabled", repKbId, true);
        log.info("开始检索，用户查询: {} | topK: {} | minScore: {} | 扩展词: {} | 排除词: {} | kbIds: {} | 代表kbId: {} | rerankEnabled: {} | parentContextEnabled: {}",
                userQuery, topK, effectiveMinScore, expandKeywords, excludeKeywords, kbIds, repKbId, rerankEnabled, parentContextEnabled);

        // ===== 阶段1：混合检索（主路径）=====
        // 按参数指纹拆库分组检索：指纹相同的知识库合并为一次检索，
        // 指纹不同的知识库拆分检索，避免多库混查时的参数歧义（rrfK / vectorMinScore）。
        List<RankedResult> allResults = new ArrayList<>(
                retrieveByFingerprintGroups(userQuery, topK, effectiveMinScore, kbIds));

        log.info("混合检索召回 {} 个片段", allResults.size());

        // ===== 阶段1.5：父上下文扩展 =====
        // 用入库时预计算的 parent_text 替换 chunk 文本，实现零延迟的上下文补全。
        // 解决 PDF 解析将编号列表/长段落误切分导致的碎片化问题。
        if (parentContextEnabled) {
            allResults = parentContextExpander.expand(allResults);
        } else {
            log.debug("父上下文扩展已关闭（retrieval.parentContextEnabled=false），跳过 parent_text 替换");
        }

        // ===== 阶段2：排除关键词过滤 =====
        // 对片段分词后精确匹配排除词（避免子串误杀，如排除"Java"误杀"JavaScript"）
        allResults = rerankOrchestrator.filterByExcludeKeywords(allResults, excludeKeywords);

        // ===== 阶段3：Cross-Encoder Rerank 精排 =====
        boolean rerankApplied = false;
        if (rerankEnabled && rerankerService.isAvailable() && !allResults.isEmpty()) {
            rerankApplied = true;
            log.info("开始 Rerank 精排，候选片段数: {}", allResults.size());

            allResults = rerankOrchestrator.rerank(userQuery, allResults);

            log.info("Rerank 精排完成，Top-3 分数: {}",
                    allResults.stream().limit(3)
                            .map(r -> String.format("%.4f", r.getScore()))
                            .collect(Collectors.joining(", ")));
        } else if (rerankEnabled && !rerankerService.isAvailable()) {
            log.warn("rerank.enabled 已开启（代表kbId={}）但 Reranker 模型未加载（yml reranker.enabled=false 或初始化失败），跳过精排", repKbId);
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
        log.info("分数重排序完成，Top-3 分数: {}",
                allResults.stream().limit(3)
                        .map(r -> String.format("%.4f", r.getScore()))
                        .collect(Collectors.joining(", ")));

        // ===== 阶段4：分数阈值过滤 =====
        allResults = rerankOrchestrator.filterByMinScore(allResults, rerankMinScore);

        // ===== 阶段5：content_type 降权 =====
        // 对非正文内容（题目、词汇表、答案）降低分数，确保正文优先
        allResults = rerankOrchestrator.applyContentTypeWeighting(allResults, repKbId);

        log.info("最终返回 {} 个片段", allResults.size());
        return allResults;
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
     * <p>显式传入 {@code rrfK}，供拆库按参数指纹分组检索时，
     * 不同参数分组的知识库使用各自的 RRF 平滑系数，避免多库混查的参数歧义。</p>
     *
     * <p>并行发起向量路径与关键词路径检索；混合检索关闭或关键词索引为空时降级为纯向量检索。</p>
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
        if (!rankFusion.isHybridEnabled() || chunkIndexManager.getKeywordIndex().size() == 0) {
            log.info("混合检索已关闭或关键词索引为空，降级为纯向量检索");
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

    /**
     * <h3>按参数指纹拆库分组检索</h3>
     *
     * <p>多库混查时，不同知识库可能配置了不同的 {@code retrieval.rrfK} /
     * {@code retrieval.vectorMinScore}。若合并为一次检索，只能取单一参数值，
     * 会产生「参数歧义」（部分库的阈值被错误应用到其他库）。</p>
     *
     * <p>本方法以 {@link ParamService#fingerprint(Long)} 生成的参数指纹为分组键：
     * 指纹相同的知识库合并为一次检索（共享同一组参数），指纹不同的知识库拆分检索，
     * 各组结果直接合并返回，由调用方统一重排。</p>
     *
     * <h4>分支规则</h4>
     * <ul>
     *   <li>{@code kbIds == null}：全局检索（不过滤知识库），读取全局 {@code retrieval.rrfK}，
     *       向量阈值沿用调用方传入的 {@code effectiveMinScore}。</li>
     *   <li>{@code kbIds} 为空集合：无可见知识库，返回空列表。</li>
     *   <li>否则：按指纹分组，每组取代表 kbId 读取该组的 {@code rrfK} 与 {@code vectorMinScore}
     *       （兜底分别为 yml 默认值与 {@code effectiveMinScore}），分别检索后合并。</li>
     * </ul>
     *
     * @param query             用户查询文本
     * @param topK              每组返回结果数量上限
     * @param effectiveMinScore 向量最低相似度兜底阈值（调用方已解析）
     * @param kbIds             知识库范围（null 表示不过滤）
     * @return 合并后的检索结果（尚未跨库重排）
     */
    private List<RankedResult> retrieveByFingerprintGroups(
            String query, int topK, double effectiveMinScore, Set<Long> kbIds) {
        if (kbIds == null) {
            int globalRrfK = paramValueProvider.getInt(
                    "retrieval.rrfK", null, rankFusion.getDefaultRrfK());
            return hybridSearchRanked(
                    query, topK, effectiveMinScore, null, globalRrfK);
        }
        if (kbIds.isEmpty()) {
            return Collections.emptyList();
        }
        // 按参数指纹分组（LinkedHashMap 保持分组顺序稳定）
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        for (Long kbId : kbIds) {
            groups.computeIfAbsent(paramService.fingerprint(kbId), k -> new ArrayList<>()).add(kbId);
        }
        List<RankedResult> merged = new ArrayList<>();
        for (List<Long> group : groups.values()) {
            Long rep = group.get(0);
            int rrfK = paramValueProvider.getInt("retrieval.rrfK", rep, rankFusion.getDefaultRrfK());
            double groupMin = paramValueProvider.getDouble("retrieval.vectorMinScore", rep, effectiveMinScore);
            merged.addAll(hybridSearchRanked(
                    query, topK, groupMin, new HashSet<>(group), rrfK));
        }
        return merged;
    }

    // ==================== 分路观测检索（召回测试复用） ====================

    /**
     * <h3>带分路观测的检索（供召回测试复用）</h3>
     *
     * <p>将向量 / BM25 单路检索、RRF 融合、Rerank 精排统一编排在本服务内，
     * 复用检索原语与精排能力，避免召回测试独立实现重复逻辑。</p>
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
        // 代表 kbId（方案 A）：全局倾向参数（父上下文开关 / content_type 权重）按代表库读取，无覆盖则回退全局
        Long repKbId = representativeKbId(kbIds);
        boolean parentContextEnabled = paramValueProvider.getBoolean(
                "retrieval.parentContextEnabled", repKbId, true);

        List<RankedResult> vector = new ArrayList<>();
        List<RankedResult> bm25 = new ArrayList<>();
        Long vectorMs = null;
        Long bm25Ms = null;

        if (needVector && needBm25) {
            CompletableFuture<TimedResult> vectorFuture = CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                List<RankedResult> r =
                        traceVectorRetrieve(query, topK, kbIds, documentIds, threshold);
                return new TimedResult(r, System.currentTimeMillis() - start);
            });
            CompletableFuture<TimedResult> bm25Future = CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                List<RankedResult> r =
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

        List<RankedResult> fusion = new ArrayList<>();
        if (needVector && needBm25) {
            fusion = rankFusion.fuseRanked(vector, bm25, topK, rrfK);
        }

        // ===== 父上下文扩展（对齐生产阶段1.5）：用入库预计算的 parent_text 替换片段文本 =====
        if (parentContextEnabled) {
            fusion = parentContextExpander.expand(fusion);
        } else {
            log.debug("召回测试-父上下文扩展已关闭（retrieval.parentContextEnabled=false），跳过 parent_text 替换");
        }

        // ===== 排除关键词过滤（对齐生产阶段2）：分词后精确匹配排除词，避免子串误杀 =====
        fusion = rerankOrchestrator.filterByExcludeKeywords(fusion, excludeKeywords);

        List<RerankedResult> rerank = new ArrayList<>();
        Long rerankMs = null;
        if (needVector && needBm25 && enableRerank) {
            long start = System.currentTimeMillis();
            rerank = traceRerank(query, fusion, rerankThreshold, repKbId);
            rerankMs = System.currentTimeMillis() - start;
        } else if (needVector && needBm25) {
            // ===== 跨库重排（对齐生产最终重排序）：Rerank 关闭时按原始向量相似度排序，避免用 RRF 分混排 =====
            fusion = rerankOrchestrator.reorderByRawScore(fusion);
            // ===== content_type 降权（对齐生产阶段5）：Rerank 关闭时，融合结果即最终结果 =====
            fusion = rerankOrchestrator.applyContentTypeWeighting(fusion, repKbId);
        }

        return new RetrievalTrace(vector, bm25, fusion, rerank, vectorMs, bm25Ms, rerankMs);
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

    private List<RerankedResult> traceRerank(String query, List<RankedResult> fusion,
                                             double rerankThreshold, Long repKbId) {
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
            List<RerankedResult> items = new ArrayList<>();
            for (RerankerService.RerankResult rr : rerankResults) {
                if (rr.getScore() < rerankThreshold) {
                    continue;
                }
                RankedResult base = fusion.get(rr.getIndex());
                items.add(new RerankedResult(base.getSegment(), rr.getScore(), rr.getIndex() + 1));
            }
            // ===== content_type 降权（对齐生产阶段5）：非正文片段（题目/词汇/答案）降权后重排 =====
            return rerankOrchestrator.applyContentTypeWeightingToReranked(items, repKbId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("召回测试-Rerank重排序失败", e);
            throw new BusinessException(500, "Rerank 重排序失败");
        }
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
