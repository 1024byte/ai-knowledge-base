package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.common.KeywordIndex;
import com.hai.aiknowledgebase.dto.RecallTestRequest;
import com.hai.aiknowledgebase.dto.RecallTestResponse;
import com.hai.aiknowledgebase.entity.KnowledgeBase;
import com.hai.aiknowledgebase.entity.KnowledgeDocument;
import com.hai.aiknowledgebase.entity.KnowledgeDocumentChunk;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.KnowledgeBaseMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeDocumentChunkMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeDocumentMapper;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * <h2>召回测试服务</h2>
 *
 * <p>在服务内部独立编排「向量 / BM25 / 混合」三路召回流程，不侵入既有 {@link HybridSearchService}，
 * 用于召回测试页验证知识片段在多路检索下的召回命中效果。</p>
 *
 * <h3>阶段划分</h3>
 * <ul>
 *   <li><b>阶段1</b>：接口契约 + DTO + 参数校验 + 控制器骨架（已完成）</li>
 *   <li><b>阶段2</b>：检索范围解析 + 单路向量/BM25 检索 + BM25 归一化 + 阈值过滤 + tokens + 计时（已完成）</li>
 *   <li><b>阶段3</b>：RRF 融合 + 数据补全 + 三路组装</li>
 *   <li><b>阶段4</b>：异常兜底 + 验证</li>
 *   <li><b>扩展</b>：Rerank 精排（hybrid 且 enableRerank=true 时生效）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecallTestService {

    private final HybridSearchService hybridSearchService;
    private final ChineseTokenizerService tokenizerService;
    private final KnowledgeDocumentChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final RerankerService rerankerService;

    /**
     * 执行一次召回测试。
     *
     * <p>阶段 3 完成：单路检索（阶段2）、数据补全（chunkId/documentId/source/kbName）、
     * hybrid 策略下的 RRF 融合排序，以及三路结果组装。</p>
     */
    public RecallTestResponse run(RecallTestRequest request) {
        long totalStart = System.currentTimeMillis();

        String query = request.getQuery();
        String strategy = request.getStrategy();
        int topK = request.getTopK();
        double threshold = request.getThreshold();
        int rrfK = request.getRrfK() == null ? 60 : request.getRrfK();

        // 1. 检索范围解析：kbIds 必选；documentIds 为空表示所选知识库下全部文档（不过滤文档范围）
        Set<Long> kbIds = new HashSet<>(request.getKbIds());
        Set<Long> documentIds = (request.getDocumentIds() == null || request.getDocumentIds().isEmpty())
                ? null
                : new HashSet<>(request.getDocumentIds());

        // 2. BM25 分词结果（vector 策略返回空数组）
        List<String> tokens = "vector".equals(strategy)
                ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(tokenizerService.tokenize(query, true)));

        // 3. 单路检索 + 分路计时
        List<VectorHit> vectorHits = new ArrayList<>();
        List<Bm25Hit> bm25Hits = new ArrayList<>();
        Long vectorMs = null;
        Long bm25Ms = null;

        if ("hybrid".equals(strategy)) {
            try {
                CompletableFuture<TimedResult<List<VectorHit>>> vectorFuture = CompletableFuture.supplyAsync(() -> {
                    long start = System.currentTimeMillis();
                    List<VectorHit> hits = vectorRetrieve(query, topK, kbIds, documentIds, threshold);
                    return new TimedResult<>(hits, System.currentTimeMillis() - start);
                });
                CompletableFuture<TimedResult<List<Bm25Hit>>> bm25Future = CompletableFuture.supplyAsync(() -> {
                    long start = System.currentTimeMillis();
                    List<Bm25Hit> hits = bm25Retrieve(query, topK, kbIds, documentIds, threshold);
                    return new TimedResult<>(hits, System.currentTimeMillis() - start);
                });

                TimedResult<List<VectorHit>> vectorResult = vectorFuture.join();
                TimedResult<List<Bm25Hit>> bm25Result = bm25Future.join();
                vectorHits = vectorResult.value;
                vectorMs = vectorResult.elapsedMs;
                bm25Hits = bm25Result.value;
                bm25Ms = bm25Result.elapsedMs;
            } catch (CompletionException e) {
                throw unwrapCompletionException(e);
            }
        } else if ("vector".equals(strategy)) {
            vectorHits = vectorRetrieve(query, topK, kbIds, documentIds, threshold);
        } else {
            bm25Hits = bm25Retrieve(query, topK, kbIds, documentIds, threshold);
        }

        // 4. 数据补全：chunkId / documentId / source / kbName
        CompletionContext context = buildCompletionContext(vectorHits, bm25Hits);
        List<RecallTestResponse.RecallResultItem> vectorItems = completeVector(vectorHits, context);
        List<RecallTestResponse.RecallResultItem> bm25Items = completeBm25(bm25Hits, context);

        // 5. RRF 融合（仅 hybrid）
        List<RecallTestResponse.RecallResultItem> fusionItems = "hybrid".equals(strategy)
                ? fuse(vectorItems, bm25Items, rrfK, topK)
                : new ArrayList<>();

        // 6. Rerank 精排（仅 hybrid 且 enableRerank=true）
        List<RecallTestResponse.RecallResultItem> rerankItems = new ArrayList<>();
        Long rerankMs = null;
        if ("hybrid".equals(strategy) && request.isEnableRerank()) {
            long rerankStart = System.currentTimeMillis();
            double rerankThreshold = request.getRerankThreshold() == null ? 0.5 : request.getRerankThreshold();
            rerankItems = rerank(fusionItems, query, rerankThreshold);
            rerankMs = System.currentTimeMillis() - rerankStart;
        }

        long totalMs = System.currentTimeMillis() - totalStart;

        log.info("召回测试完成: query={}, strategy={}, 向量命中={}, BM25命中={}, 融合={}, 重排={}, 总耗时={}ms",
                query, strategy, vectorHits.size(), bm25Hits.size(), fusionItems.size(), rerankItems.size(), totalMs);

        return RecallTestResponse.builder()
                .query(query)
                .strategy(strategy)
                .totalMs(totalMs)
                .vectorMs(vectorMs)
                .bm25Ms(bm25Ms)
                .rerankMs(rerankMs)
                .tokens(tokens)
                .results(RecallTestResponse.Results.builder()
                        .fusion(fusionItems)
                        .vector(vectorItems)
                        .bm25(bm25Items)
                        .rerank(rerankItems)
                        .build())
                .build();
    }

    /**
     * 单路向量检索：委托 {@link HybridSearchService#vectorSearchRanked}，按阈值过滤后包装为内部命中结构。
     */
    private List<VectorHit> vectorRetrieve(String query, int topK, Set<Long> kbIds,
                                           Set<Long> documentIds, double threshold) {
        List<HybridSearchService.RankedResult> ranked;
        try {
            ranked = hybridSearchService.vectorSearchRanked(query, topK, kbIds, documentIds);
        } catch (Exception e) {
            log.error("召回测试-向量检索失败: query={}", query, e);
            throw new BusinessException(500, "向量检索失败");
        }

        List<VectorHit> hits = new ArrayList<>();
        for (HybridSearchService.RankedResult result : ranked) {
            if (result.getScore() < threshold) {
                continue;
            }
            hits.add(new VectorHit(chunkIdOf(result.getSegment()), result.getSegment(), result.getScore()));
        }
        return hits;
    }

    /**
     * 单路 BM25 检索：委托 {@link KeywordIndex#search}，将分数归一化到 0~1 后按阈值过滤。
     */
    private List<Bm25Hit> bm25Retrieve(String query, int topK, Set<Long> kbIds,
                                       Set<Long> documentIds, double threshold) {
        try {
            KeywordIndex keywordIndex = hybridSearchService.getKeywordIndex();
            if (keywordIndex.size() == 0) {
                return new ArrayList<>();
            }

            Map<Long, Long> chunkIdToDocumentId = resolveChunkIdToDocumentId(documentIds);
            List<Map.Entry<String, Double>> entries =
                    keywordIndex.search(query, topK, kbIds, documentIds, chunkIdToDocumentId);
            if (entries.isEmpty()) {
                return new ArrayList<>();
            }

            // BM25 归一化：除以本轮命中的最大原始分数，映射到 0~1 区间
            double maxRawScore = entries.stream()
                    .mapToDouble(Map.Entry::getValue)
                    .max()
                    .orElse(0);
            if (maxRawScore <= 0) {
                return new ArrayList<>();
            }

            List<Bm25Hit> hits = new ArrayList<>();
            for (Map.Entry<String, Double> entry : entries) {
                double normalized = entry.getValue() / maxRawScore;
                if (normalized < threshold) {
                    continue;
                }
                String text = keywordIndex.getDocText(entry.getKey());
                if (text == null) {
                    continue;
                }
                hits.add(new Bm25Hit(entry.getKey(), chunkIdFromDocId(entry.getKey()), normalized, text));
            }
            return hits;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("召回测试-BM25检索失败: query={}", query, e);
            throw new BusinessException(500, "BM25 检索失败");
        }
    }

    /**
     * 构建「片段主键 → 文档 ID」映射，供 BM25 检索按文档范围过滤。
     *
     * @param documentIds 文档 ID 集合（null/空返回空映射）
     */
    private Map<Long, Long> resolveChunkIdToDocumentId(Set<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = chunkMapper.selectChunkIdDocumentIdByDocumentIds(documentIds);
        Map<Long, Long> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("id");
            Object documentIdObj = row.get("document_id");
            if (idObj == null || documentIdObj == null) {
                continue;
            }
            map.put(((Number) idObj).longValue(), ((Number) documentIdObj).longValue());
        }
        return map;
    }

    // ==================== 数据补全 ====================

    /**
     * 构建数据补全上下文：以片段主键为锚点，逐级反查「片段 → 文档 → 知识库」，
     * 得到 documentId / fileName（source）/ kbName。
     */
    private CompletionContext buildCompletionContext(List<VectorHit> vectorHits, List<Bm25Hit> bm25Hits) {
        Set<Long> chunkIds = new HashSet<>();
        for (VectorHit hit : vectorHits) {
            if (hit.chunkId != null) {
                chunkIds.add(hit.chunkId);
            }
        }
        for (Bm25Hit hit : bm25Hits) {
            if (hit.chunkId != null) {
                chunkIds.add(hit.chunkId);
            }
        }

        // 片段 → 文档
        Map<Long, Long> chunkToDocument = new HashMap<>();
        if (!chunkIds.isEmpty()) {
            for (KnowledgeDocumentChunk chunk : chunkMapper.selectBatchIds(chunkIds)) {
                if (chunk != null) {
                    chunkToDocument.put(chunk.getId(), chunk.getDocumentId());
                }
            }
        }

        // 文档 → (fileName, kbId)
        Set<Long> documentIds = chunkToDocument.values().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, KnowledgeDocument> documentMap = new HashMap<>();
        if (!documentIds.isEmpty()) {
            for (KnowledgeDocument document : documentMapper.selectBatchIds(documentIds)) {
                if (document != null) {
                    documentMap.put(document.getId(), document);
                }
            }
        }

        // 知识库 → name
        Set<Long> kbIds = documentMap.values().stream()
                .map(KnowledgeDocument::getKbId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, KnowledgeBase> kbMap = new HashMap<>();
        if (!kbIds.isEmpty()) {
            for (KnowledgeBase kb : kbMapper.selectBatchIds(kbIds)) {
                if (kb != null) {
                    kbMap.put(kb.getId(), kb);
                }
            }
        }

        return new CompletionContext(chunkToDocument, documentMap, kbMap);
    }

    private List<RecallTestResponse.RecallResultItem> completeVector(List<VectorHit> hits, CompletionContext context) {
        return hits.stream()
                .map(hit -> buildItem(hit.chunkId, hit.segment.text(), hit.score, context))
                .collect(Collectors.toList());
    }

    private List<RecallTestResponse.RecallResultItem> completeBm25(List<Bm25Hit> hits, CompletionContext context) {
        return hits.stream()
                .map(hit -> buildItem(hit.chunkId, hit.text, hit.score, context))
                .collect(Collectors.toList());
    }

    /**
     * 依据补全上下文组装单个结果项。
     */
    private RecallTestResponse.RecallResultItem buildItem(Long chunkId, String content, double score,
                                                          CompletionContext context) {
        Long documentId = chunkId == null ? null : context.chunkToDocument.get(chunkId);
        KnowledgeDocument document = documentId == null ? null : context.documentMap.get(documentId);
        Long kbId = document == null ? null : document.getKbId();
        KnowledgeBase kb = kbId == null ? null : context.kbMap.get(kbId);

        return RecallTestResponse.RecallResultItem.builder()
                .chunkId(chunkId)
                .documentId(documentId)
                .source(document != null ? document.getFileName() : null)
                .kbName(kb != null ? kb.getName() : null)
                .content(content)
                .score(score)
                .build();
    }

    // ==================== RRF 融合 ====================

    /**
     * <h3>RRF（Reciprocal Rank Fusion）倒数排名融合</h3>
     *
     * <p>以片段主键为去重键，对向量、BM25 两路排名累加 RRF 分数：</p>
     * <pre>RRF_score(d) = Σ 1 / (rrfK + rank_i(d))</pre>
     *
     * <p>融合结果携带 {@code vectorRank} / {@code bm25Rank}，仅单路命中时另一路排名为 null；
     * 得分替换为 RRF 分数（小数值），内容与 DB 补全字段沿用优先命中路径的结果。</p>
     */
    private List<RecallTestResponse.RecallResultItem> fuse(List<RecallTestResponse.RecallResultItem> vectorItems,
                                                           List<RecallTestResponse.RecallResultItem> bm25Items,
                                                           int rrfK, int topK) {
        try {
            Map<Long, Double> rrfScores = new LinkedHashMap<>();
            Map<Long, RecallTestResponse.RecallResultItem> vectorByChunk = new LinkedHashMap<>();
            Map<Long, RecallTestResponse.RecallResultItem> bm25ByChunk = new LinkedHashMap<>();
            Map<Long, Integer> vectorRankMap = new HashMap<>();
            Map<Long, Integer> bm25RankMap = new HashMap<>();

            for (int i = 0; i < vectorItems.size(); i++) {
                Long chunkId = vectorItems.get(i).getChunkId();
                if (chunkId == null) {
                    continue;
                }
                vectorByChunk.putIfAbsent(chunkId, vectorItems.get(i));
                vectorRankMap.putIfAbsent(chunkId, i + 1);
                rrfScores.merge(chunkId, 1.0 / (rrfK + i + 1), Double::sum);
            }

            for (int i = 0; i < bm25Items.size(); i++) {
                Long chunkId = bm25Items.get(i).getChunkId();
                if (chunkId == null) {
                    continue;
                }
                bm25ByChunk.putIfAbsent(chunkId, bm25Items.get(i));
                bm25RankMap.putIfAbsent(chunkId, i + 1);
                rrfScores.merge(chunkId, 1.0 / (rrfK + i + 1), Double::sum);
            }

            return rrfScores.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(topK)
                    .map(entry -> {
                        Long chunkId = entry.getKey();
                        RecallTestResponse.RecallResultItem base = vectorByChunk.getOrDefault(chunkId, bm25ByChunk.get(chunkId));
                        return RecallTestResponse.RecallResultItem.builder()
                                .chunkId(chunkId)
                                .documentId(base.getDocumentId())
                                .source(base.getSource())
                                .kbName(base.getKbName())
                                .content(base.getContent())
                                .score(entry.getValue())
                                .vectorRank(vectorRankMap.get(chunkId))
                                .bm25Rank(bm25RankMap.get(chunkId))
                                .build();
                    })
                    .collect(Collectors.toList());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("召回测试-RRF融合失败", e);
            throw new BusinessException(500, "RRF 融合失败");
        }
    }

    /**
     * Rerank 精排：对 RRF 融合结果调用 Cross-Encoder 精排，按 rerankScore 降序、rerankThreshold 过滤。
     *
     * <p>仅当 {@link RerankerService} 可用时执行；不可用时抛 500「Rerank 重排序失败」。
     * 复用 fusion 项的全部基础字段（chunkId/documentId/source/kbName/content/score/vectorRank/bm25Rank），
     * 并补齐 rerankScore / coarseRank / rerankRank 三个精排字段。</p>
     */
    private List<RecallTestResponse.RecallResultItem> rerank(
            List<RecallTestResponse.RecallResultItem> fusionItems,
            String query, double rerankThreshold) {
        if (!rerankerService.isAvailable()) {
            log.error("召回测试-Rerank重排序失败: Reranker 不可用");
            throw new BusinessException(500, "Rerank 重排序失败");
        }
        if (fusionItems.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            List<String> docTexts = fusionItems.stream()
                    .map(RecallTestResponse.RecallResultItem::getContent)
                    .collect(Collectors.toList());

            // rerankerService.rerank 内部已按分数降序排序，index 保留其在 fusion 中的原始下标
            List<RerankerService.RerankResult> rerankResults =
                    rerankerService.rerank(query, docTexts, docTexts.size());

            List<RecallTestResponse.RecallResultItem> items = new ArrayList<>();
            int rerankRank = 1;
            for (RerankerService.RerankResult rr : rerankResults) {
                if (rr.getScore() < rerankThreshold) {
                    continue;
                }
                RecallTestResponse.RecallResultItem base = fusionItems.get(rr.getIndex());
                items.add(RecallTestResponse.RecallResultItem.builder()
                        .chunkId(base.getChunkId())
                        .documentId(base.getDocumentId())
                        .source(base.getSource())
                        .kbName(base.getKbName())
                        .content(base.getContent())
                        .score(base.getScore())
                        .vectorRank(base.getVectorRank())
                        .bm25Rank(base.getBm25Rank())
                        .rerankScore(rr.getScore())
                        .coarseRank(rr.getIndex() + 1)
                        .rerankRank(rerankRank++)
                        .build());
            }
            return items;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("召回测试-Rerank重排序失败", e);
            throw new BusinessException(500, "Rerank 重排序失败");
        }
    }

    // ==================== 解析工具 ====================

    /**
     * 从 TextSegment 元数据中解析片段主键（向量侧）。
     */
    private Long chunkIdOf(TextSegment segment) {
        return parseLong(segment.metadata().getString("chunk_id"));
    }

    /**
     * 从 BM25 docId（格式 {@code "kbId_source_chunkId"}）中解析片段主键（最后一个下划线之后）。
     */
    private Long chunkIdFromDocId(String docId) {
        if (docId == null || docId.isEmpty()) {
            return null;
        }
        int idx = docId.lastIndexOf('_');
        if (idx < 0 || idx == docId.length() - 1) {
            return null;
        }
        return parseLong(docId.substring(idx + 1));
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 异常处理 ====================

    /**
     * 解开 hybrid 并行检索时 {@link CompletableFuture#join()} 抛出的 {@link CompletionException}，
     * 还原内部的 {@link BusinessException}（如「向量检索失败」「BM25 检索失败」），
     * 避免异常类型被包装后丢失具体错误信息。
     */
    private BusinessException unwrapCompletionException(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof BusinessException businessException) {
            return businessException;
        }
        log.error("召回测试-并行检索执行失败", e);
        return new BusinessException(500, "召回测试执行失败");
    }

    // ==================== 内部结构 ====================

    /** 带耗时的结果包装，用于 hybrid 并行检索时的分路计时 */
    private static class TimedResult<T> {
        final T value;
        final long elapsedMs;

        TimedResult(T value, long elapsedMs) {
            this.value = value;
            this.elapsedMs = elapsedMs;
        }
    }

    /** 向量单路命中：保留 TextSegment（含元数据）与片段主键 */
    private static class VectorHit {
        final Long chunkId;
        final TextSegment segment;
        final double score;

        VectorHit(Long chunkId, TextSegment segment, double score) {
            this.chunkId = chunkId;
            this.segment = segment;
            this.score = score;
        }
    }

    /** BM25 单路命中：保留 docId、片段主键与归一化后分数 */
    private static class Bm25Hit {
        final String docId;
        final Long chunkId;
        final double score;
        final String text;

        Bm25Hit(String docId, Long chunkId, double score, String text) {
            this.docId = docId;
            this.chunkId = chunkId;
            this.score = score;
            this.text = text;
        }
    }

    /** 数据补全上下文：片段 → 文档 → 知识库 三级映射 */
    private static class CompletionContext {
        final Map<Long, Long> chunkToDocument;
        final Map<Long, KnowledgeDocument> documentMap;
        final Map<Long, KnowledgeBase> kbMap;

        CompletionContext(Map<Long, Long> chunkToDocument,
                          Map<Long, KnowledgeDocument> documentMap,
                          Map<Long, KnowledgeBase> kbMap) {
            this.chunkToDocument = chunkToDocument;
            this.documentMap = documentMap;
            this.kbMap = kbMap;
        }
    }
}