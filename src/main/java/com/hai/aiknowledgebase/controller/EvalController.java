package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.EvalRetrieveRequest;
import com.hai.aiknowledgebase.dto.EvalRetrieveResponse;
import com.hai.aiknowledgebase.dto.EvalRetrieveResponse.ExcludeFilterResult;
import com.hai.aiknowledgebase.dto.EvalRetrieveResponse.RetrievedDoc;
import com.hai.aiknowledgebase.dto.EvalRetrieveResponse.StageResult;
import com.hai.aiknowledgebase.dto.QueryRewriteResult;
import com.hai.aiknowledgebase.dto.RewriteRequest;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.queryrewrite.QueryRewriteService;
import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import com.hai.aiknowledgebase.service.HybridSearchService;
import com.hai.aiknowledgebase.service.RAGSearchService;
import com.hai.aiknowledgebase.service.RerankerService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
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

    private final RAGSearchService ragSearchService;
    private final HybridSearchService hybridSearchService;
    private final QueryRewriteService queryRewriteService;
    private final RerankerService rerankerService;
    private final ChineseTokenizerService tokenizerService;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    private static final double MIN_VECTOR_SCORE = 0.3;

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
        List<HybridSearchService.RankedResult> hybridResults;
        if (doHybrid) {
            hybridResults = new ArrayList<>(
                    hybridSearchService.hybridSearchRanked(searchQuery, topK, MIN_VECTOR_SCORE));
        } else {
            hybridResults = vectorOnlySearch(searchQuery, topK);
        }
        builder.hybrid(toStageResult(hybridResults));

        // 阶段2：排除关键词过滤
        int beforeFilter = hybridResults.size();
        List<Integer> removedIndices = new ArrayList<>();
        List<HybridSearchService.RankedResult> filteredResults = hybridResults;

        if (excludeKeywords != null && !excludeKeywords.isEmpty()) {
            filteredResults = new ArrayList<>();
            for (int i = 0; i < hybridResults.size(); i++) {
                HybridSearchService.RankedResult rr = hybridResults.get(i);
                String text = rr.getSegment().text();
                List<String> segTokens = tokenizerService.tokenize(text, true);
                Set<String> tokenSet = new HashSet<>(segTokens);
                boolean excluded = false;
                for (String exclude : excludeKeywords) {
                    if (tokenSet.contains(exclude)) {
                        excluded = true;
                        break;
                    }
                }
                if (excluded) {
                    removedIndices.add(i);
                } else {
                    filteredResults.add(rr);
                }
            }
            if (filteredResults.isEmpty() && beforeFilter > 0) {
                log.warn("排除关键词过滤导致所有片段被剔除({} -> 0)，降级保留原始结果", beforeFilter);
                filteredResults = hybridResults;
                removedIndices.clear();
            }
        }
        builder.excludeFilter(ExcludeFilterResult.builder()
                .beforeCount(beforeFilter)
                .afterCount(filteredResults.size())
                .removedIndices(removedIndices)
                .build());

        // 阶段3：Rerank 精排
        List<HybridSearchService.RankedResult> rerankedResults = filteredResults;
        if (doRerank && rerankerService.isAvailable() && !filteredResults.isEmpty()) {
            List<String> docTexts = filteredResults.stream()
                    .map(r -> r.getSegment().text())
                    .collect(Collectors.toList());

            List<RerankerService.RerankResult> rerankResults = rerankerService.rerank(searchQuery, docTexts, filteredResults.size());
            rerankedResults = new ArrayList<>();
            for (RerankerService.RerankResult rr : rerankResults) {
                HybridSearchService.RankedResult original = filteredResults.get(rr.getIndex());
                rerankedResults.add(new HybridSearchService.RankedResult(original.getSegment(), rr.getScore()));
            }
        }
        builder.rerank(toStageResult(rerankedResults));

        // 最终排序
        rerankedResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        builder.finalRanked(toRetrievedDocList(rerankedResults));

        return builder.build();
    }

    private List<HybridSearchService.RankedResult> vectorOnlySearch(String query, int topK) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();
        List<HybridSearchService.RankedResult> results = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            results.add(new HybridSearchService.RankedResult(match.embedded(), match.score()));
        }
        return results;
    }

    private StageResult toStageResult(List<HybridSearchService.RankedResult> results) {
        return StageResult.builder()
                .results(toRetrievedDocList(results))
                .count(results.size())
                .build();
    }

    private List<RetrievedDoc> toRetrievedDocList(List<HybridSearchService.RankedResult> results) {
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