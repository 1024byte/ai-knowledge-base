package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.config.ThresholdsConfig;
import com.hai.aiknowledgebase.dto.RecallTestRequest;
import com.hai.aiknowledgebase.dto.RecallTestResponse;
import com.hai.aiknowledgebase.entity.KnowledgeBase;
import com.hai.aiknowledgebase.entity.KnowledgeDocument;
import com.hai.aiknowledgebase.entity.KnowledgeDocumentChunk;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <h2>召回测试服务</h2>
 *
 * <p>复用 {@link RAGSearchService#retrieveWithTrace} 的生产检索编排（向量 / BM25 / RRF 融合 / Rerank），
 * 仅保留「分词观测 + 数据补全 + 响应组装」等测试展示职责，确保召回测试观测到的检索行为
 * 与智慧问答生产链路使用同一套底层检索原语。</p>
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li><b>检索编排</b>：委托 {@link RAGSearchService#retrieveWithTrace}（单路检索、阈值过滤、融合、精排）</li>
 *   <li><b>本服务保留</b>：BM25 分词观测、chunkId/documentId/source/kbName 数据补全、三路响应组装</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecallTestService {

    private final RAGSearchService ragSearchService;
    private final ChineseTokenizerService tokenizerService;
    private final KnowledgeDocumentChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ThresholdsConfig thresholdsConfig;
    private final ParamValueProvider paramValueProvider;

    /**
     * 执行一次召回测试。
     */
    public RecallTestResponse run(RecallTestRequest request) {
        long totalStart = System.currentTimeMillis();

        String query = request.getQuery();
        String strategy = request.getStrategy();
        int topK = request.getTopK();
        double threshold = request.getThreshold();
        int rrfK = request.getRrfK() == null ? 60 : request.getRrfK();
        boolean enableRerank = request.isEnableRerank();
        double rerankThreshold = request.getRerankThreshold() == null
                ? paramValueProvider.getDouble("rerank.minScore",
                        (request.getKbIds() == null || request.getKbIds().isEmpty())
                                ? null : Collections.min(request.getKbIds()),
                        thresholdsConfig.getReranker().getMinScore())
                : request.getRerankThreshold();

        // 1. 检索范围解析：kbIds 必选；documentIds 为空表示所选知识库下全部文档（不过滤文档范围）
        Set<Long> kbIds = new HashSet<>(request.getKbIds());
        Set<Long> documentIds = (request.getDocumentIds() == null || request.getDocumentIds().isEmpty())
                ? null
                : new HashSet<>(request.getDocumentIds());

        // 排除关键词（对齐生产链路阶段2，空表示不过滤）
        List<String> excludeKeywords = (request.getExcludeKeywords() == null || request.getExcludeKeywords().isEmpty())
                ? null
                : new ArrayList<>(request.getExcludeKeywords());

        // 2. BM25 分词结果（vector 策略返回空数组）
        List<String> tokens = "vector".equals(strategy)
                ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(tokenizerService.tokenize(query, true)));

        // 3. 复用生产检索编排，一次性拿到向量/BM25/融合/精排四路观测结果
        RAGSearchService.RetrievalTrace trace = ragSearchService.retrieveWithTrace(
                query, strategy, topK, threshold, kbIds, documentIds, rrfK, enableRerank, rerankThreshold,
                excludeKeywords);

        // 4. 数据补全：以片段主键为锚点反查 documentId / source / kbName
        CompletionContext context = buildCompletionContext(trace);
        List<RecallTestResponse.RecallResultItem> vectorItems = complete(trace.getVector(), context);
        List<RecallTestResponse.RecallResultItem> bm25Items = complete(trace.getBm25(), context);
        List<RecallTestResponse.RecallResultItem> fusionItems = completeFusion(trace, context);
        List<RecallTestResponse.RecallResultItem> rerankItems = completeRerank(trace, context, fusionItems);

        long totalMs = System.currentTimeMillis() - totalStart;

        log.info("召回测试完成: query={}, strategy={}, 向量命中={}, BM25命中={}, 融合={}, 重排={}, 总耗时={}ms",
                query, strategy, trace.getVector().size(), trace.getBm25().size(),
                fusionItems.size(), rerankItems.size(), totalMs);

        return RecallTestResponse.builder()
                .query(query)
                .strategy(strategy)
                .totalMs(totalMs)
                .vectorMs(trace.getVectorMs())
                .bm25Ms(trace.getBm25Ms())
                .rerankMs(trace.getRerankMs())
                .tokens(tokens)
                .results(RecallTestResponse.Results.builder()
                        .fusion(fusionItems)
                        .vector(vectorItems)
                        .bm25(bm25Items)
                        .rerank(rerankItems)
                        .build())
                .build();
    }

    // ==================== 数据补全 ====================

    /**
     * 构建数据补全上下文：以片段主键为锚点，逐级反查「片段 → 文档 → 知识库」，
     * 得到 documentId / fileName（source）/ kbName。
     */
    private CompletionContext buildCompletionContext(RAGSearchService.RetrievalTrace trace) {
        Set<Long> chunkIds = new HashSet<>();
        for (HybridSearchService.RankedResult r : trace.getVector()) {
            addChunkId(chunkIds, r.getSegment());
        }
        for (HybridSearchService.RankedResult r : trace.getBm25()) {
            addChunkId(chunkIds, r.getSegment());
        }
        for (HybridSearchService.RankedResult r : trace.getFusion()) {
            addChunkId(chunkIds, r.getSegment());
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

    private void addChunkId(Set<Long> chunkIds, TextSegment segment) {
        Long chunkId = chunkIdOf(segment);
        if (chunkId != null) {
            chunkIds.add(chunkId);
        }
    }

    /**
     * 将单路检索结果组装为响应项（向量 / BM25 共用）。
     */
    private List<RecallTestResponse.RecallResultItem> complete(List<HybridSearchService.RankedResult> results,
                                                               CompletionContext context) {
        return results.stream()
                .map(r -> buildItem(chunkIdOf(r.getSegment()), r.getSegment().text(), r.getScore(), context))
                .collect(Collectors.toList());
    }

    /**
     * 组装 RRF 融合结果：补齐 vectorRank / bm25Rank。
     */
    private List<RecallTestResponse.RecallResultItem> completeFusion(RAGSearchService.RetrievalTrace trace,
                                                                     CompletionContext context) {
        Map<Long, Integer> vectorRank = rankMap(trace.getVector());
        Map<Long, Integer> bm25Rank = rankMap(trace.getBm25());

        List<RecallTestResponse.RecallResultItem> items = new ArrayList<>();
        for (HybridSearchService.RankedResult r : trace.getFusion()) {
            Long chunkId = chunkIdOf(r.getSegment());
            RecallTestResponse.RecallResultItem base =
                    buildItem(chunkId, r.getSegment().text(), r.getScore(), context);
            items.add(RecallTestResponse.RecallResultItem.builder()
                    .chunkId(chunkId)
                    .documentId(base.getDocumentId())
                    .source(base.getSource())
                    .kbName(base.getKbName())
                    .content(base.getContent())
                    .score(base.getScore())
                    .vectorRank(vectorRank.get(chunkId))
                    .bm25Rank(bm25Rank.get(chunkId))
                    .build());
        }
        return items;
    }

    /**
     * 组装 Rerank 精排结果：复用融合项的基础字段，补齐 rerankScore / coarseRank / rerankRank。
     */
    private List<RecallTestResponse.RecallResultItem> completeRerank(RAGSearchService.RetrievalTrace trace,
                                                                     CompletionContext context,
                                                                     List<RecallTestResponse.RecallResultItem> fusionItems) {
        List<RecallTestResponse.RecallResultItem> items = new ArrayList<>();
        int rerankRank = 1;
        for (RAGSearchService.RetrievalTrace.RerankedResult rr : trace.getRerank()) {
            int coarseIdx = rr.getCoarseRank() - 1;
            RecallTestResponse.RecallResultItem base = (coarseIdx >= 0 && coarseIdx < fusionItems.size())
                    ? fusionItems.get(coarseIdx)
                    : null;
            items.add(RecallTestResponse.RecallResultItem.builder()
                    .chunkId(chunkIdOf(rr.getSegment()))
                    .documentId(base != null ? base.getDocumentId() : null)
                    .source(base != null ? base.getSource() : null)
                    .kbName(base != null ? base.getKbName() : null)
                    .content(rr.getSegment().text())
                    .score(base != null ? base.getScore() : null)
                    .vectorRank(base != null ? base.getVectorRank() : null)
                    .bm25Rank(base != null ? base.getBm25Rank() : null)
                    .rerankScore(rr.getRerankScore())
                    .coarseRank(rr.getCoarseRank())
                    .rerankRank(rerankRank++)
                    .build());
        }
        return items;
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

    // ==================== 解析工具 ====================

    /**
     * 从 TextSegment 元数据中解析片段主键。
     */
    private Long chunkIdOf(TextSegment segment) {
        String value = segment.metadata().getString("chunk_id");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 构建「片段主键 → 1-based 排名」映射，用于融合结果补齐分路排名。
     */
    private Map<Long, Integer> rankMap(List<HybridSearchService.RankedResult> results) {
        Map<Long, Integer> map = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            Long chunkId = chunkIdOf(results.get(i).getSegment());
            if (chunkId != null) {
                map.putIfAbsent(chunkId, i + 1);
            }
        }
        return map;
    }

    // ==================== 内部结构 ====================

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