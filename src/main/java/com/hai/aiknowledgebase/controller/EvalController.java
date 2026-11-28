package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.config.ThresholdsConfig;
import com.hai.aiknowledgebase.dto.EvalRetrieveRequest;
import com.hai.aiknowledgebase.dto.EvalRetrieveResponse;
import com.hai.aiknowledgebase.dto.EvalRetrieveResponse.ExcludeFilterResult;
import com.hai.aiknowledgebase.dto.EvalRetrieveResponse.RetrievedDoc;
import com.hai.aiknowledgebase.dto.EvalRetrieveResponse.StageResult;
import com.hai.aiknowledgebase.dto.QueryRewriteResult;
import com.hai.aiknowledgebase.dto.RewriteRequest;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.queryrewrite.QueryRewriteService;
import com.hai.aiknowledgebase.retrieve.RankedResult;
import com.hai.aiknowledgebase.retrieve.RerankOrchestrator;
import com.hai.aiknowledgebase.retrieve.RetrievalCoordinator;
import com.hai.aiknowledgebase.retrieve.VectorRetriever;
import com.hai.aiknowledgebase.model.Reranker;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
public class EvalController {

    /** 检索协调器：混合检索编排 */
    private final RetrievalCoordinator retrievalCoordinator;
    /** 向量检索原语：vector_only 模式使用 */
    private final VectorRetriever vectorRetriever;
    private final QueryRewriteService queryRewriteService;
    private final Reranker reranker;
    /** Rerank 编排器：排除词过滤 / 精排 / 阈值过滤 */
    private final RerankOrchestrator rerankOrchestrator;
    private final ThresholdsConfig thresholdsConfig;

    @PostMapping("/retrieve")
    public Result<EvalRetrieveResponse> retrieve(@RequestBody EvalRetrieveRequest request) {
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new BusinessException(400, "查询内容不能为空");
        }

        String query = request.getQuery().trim();
        int topK = request.getTopK() > 0 ? request.getTopK() : 10;
        String mode = request.getMode() != null ? request.getMode() : "full";

        log.info("评估检索请求: query={}, topK={}, mode={}", query, topK, mode);

        EvalRetrieveResponse response = buildEvalResponse(query, topK, request.getSessionId(), mode);
        return Result.success(response);
    }

    private EvalRetrieveResponse buildEvalResponse(String query, int topK, String sessionId, String mode) {
        EvalRetrieveResponse.EvalRetrieveResponseBuilder builder = EvalRetrieveResponse.builder()
                .query(query);

        boolean doRewrite = !"vector_only".equals(mode) && !"no_rewrite".equals(mode);
        boolean doHybrid = !"vector_only".equals(mode);
        boolean doRerank = "full".equals(mode) || "no_rewrite".equals(mode);

        // 步骤0：查询改写
        String searchQuery = query;
        List<String> excludeKeywords = Collections.emptyList();
        if (doRewrite) {
            RewriteRequest rewriteRequest = RewriteRequest.builder()
                    .query(query)
                    .sessionId(sessionId)
                    .build();
            QueryRewriteResult rewriteResult = queryRewriteService.rewrite(rewriteRequest);
            searchQuery = rewriteResult.getRewrittenQuery();
            excludeKeywords = rewriteResult.getExcludeKeywords();
            builder.rewrittenQuery(searchQuery)
                    .rewritePath(rewriteResult.getPath() != null ? rewriteResult.getPath().name() : null)
                    .rewriteStrategy(rewriteResult.getStrategy() != null ? rewriteResult.getStrategy().name() : null)
                    .rewriteConfidence(rewriteResult.getConfidence())
                    .expandKeywords(rewriteResult.getExpandKeywords())
                    .excludeKeywords(excludeKeywords);
        } else {
            builder.rewrittenQuery(query)
                    .rewritePath("NONE")
                    .rewriteStrategy("NONE")
                    .rewriteConfidence(0.0)
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList());
        }

        // 阶段1：检索（向量 only 或 混合检索）
        List<RankedResult> hybridResults;
        if (doHybrid) {
            hybridResults = new ArrayList<>(
                    retrievalCoordinator.hybridSearchRanked(searchQuery, topK, thresholdsConfig.getRetrieval().getVectorMinScore()));
        } else {
            hybridResults = vectorRetriever.vectorSearchRanked(searchQuery, topK);
        }
        builder.hybrid(toStageResult(hybridResults));

        // 阶段2：排除关键词过滤（复用 RerankOrchestrator，过滤为空时降级保留原始结果）
        int beforeFilter = hybridResults.size();
        List<Integer> removedIndices = new ArrayList<>();
        List<RankedResult> filteredResults =
                rerankOrchestrator.filterByExcludeKeywords(hybridResults, excludeKeywords);
        // 发生过滤时按对象身份比对，还原被剔除片段的下标（供评估报告展示）
        if (filteredResults != hybridResults) {
            for (int i = 0; i < hybridResults.size(); i++) {
                RankedResult rr = hybridResults.get(i);
                boolean kept = filteredResults.stream().anyMatch(f -> f == rr);
                if (!kept) {
                    removedIndices.add(i);
                }
            }
        }
        builder.excludeFilter(ExcludeFilterResult.builder()
                .beforeCount(beforeFilter)
                .afterCount(filteredResults.size())
                .removedIndices(removedIndices)
                .build());

        // 阶段3：Rerank 精排（复用 RerankOrchestrator）
        List<RankedResult> rerankedResults = filteredResults;
        if (doRerank && reranker.isAvailable() && !filteredResults.isEmpty()) {
            rerankedResults = rerankOrchestrator.rerank(searchQuery, filteredResults);
        }
        builder.rerank(toStageResult(rerankedResults));

        // 阶段4：Rerank 分数阈值过滤（复用 RerankOrchestrator，全剔除时降级保留）
        double minRerankScore = thresholdsConfig.getReranker().getMinScore();
        List<RankedResult> finalResults = rerankOrchestrator.filterByMinScore(rerankedResults, minRerankScore);

        // 最终排序
        finalResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        builder.finalRanked(toRetrievedDocList(finalResults));

        return builder.build();
    }

    private StageResult toStageResult(List<RankedResult> results) {
        return StageResult.builder()
                .results(toRetrievedDocList(results))
                .count(results.size())
                .build();
    }

    private List<RetrievedDoc> toRetrievedDocList(List<RankedResult> results) {
        return results.stream()
                .map(rr -> {
                    TextSegment seg = rr.getSegment();
                    Object source = seg.metadata().getString("source");
                    Object docId = seg.metadata().getString("documentId");
                    if (docId == null) {
                        docId = seg.metadata().getString("document_id");
                    }
                    return RetrievedDoc.builder()
                            .text(seg.text())
                            .score(rr.getScore())
                            .source(source != null ? source.toString() : null)
                            .documentId(docId != null ? docId.toString() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }
}