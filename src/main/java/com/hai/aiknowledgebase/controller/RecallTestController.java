package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.config.ThresholdsConfig;
import com.hai.aiknowledgebase.dto.RecallTestRequest;
import com.hai.aiknowledgebase.dto.RecallTestResponse;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.service.ParamValueProvider;
import com.hai.aiknowledgebase.service.RecallTestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

/**
 * 召回测试接口
 */
@Slf4j
@RestController
@RequestMapping("/api/recall-test")
@RequiredArgsConstructor
public class RecallTestController {

    private static final Set<String> STRATEGIES = Set.of("hybrid", "vector", "bm25");
    private static final Set<String> RERANK_STAGES = Set.of("fuse_rerank", "rerank_fuse");

    private final RecallTestService recallTestService;
    private final ThresholdsConfig thresholdsConfig;
    private final ParamValueProvider paramValueProvider;

    @PostMapping("/run")
    public Result<RecallTestResponse> run(@RequestBody RecallTestRequest request) {
        validateAndNormalize(request);
        return Result.success(recallTestService.run(request));
    }

    private void validateAndNormalize(RecallTestRequest request) {
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new BusinessException(400, "测试问题不能为空");
        }
        if (request.getKbIds() == null || request.getKbIds().isEmpty()) {
            throw new BusinessException(400, "检索范围不能为空");
        }
        if (request.getDocumentIds() == null) {
            request.setDocumentIds(new ArrayList<>());
        }

        if (request.getStrategy() == null || request.getStrategy().isBlank()) {
            request.setStrategy("hybrid");
        } else if (!STRATEGIES.contains(request.getStrategy())) {
            throw new BusinessException(400, "不支持的检索策略");
        }

        if (request.getRerankStage() == null || request.getRerankStage().isBlank()) {
            request.setRerankStage("fuse_rerank");
        } else if (!RERANK_STAGES.contains(request.getRerankStage())) {
            throw new BusinessException(400, "不支持的 rerankStage");
        }

        if (request.getTopK() == null) {
            request.setTopK(5);
        }
        if (request.getTopK() < 1 || request.getTopK() > 50) {
            throw new BusinessException(400, "topK 必须在 1~50 之间");
        }

        if (request.getCandidateMultiplier() == null) {
            request.setCandidateMultiplier(3);
        }
        if (request.getCandidateMultiplier() < 1 || request.getCandidateMultiplier() > 10) {
            throw new BusinessException(400, "candidateMultiplier 必须在 1~10 之间");
        }
        if (request.getMaxCandidates() == null) {
            request.setMaxCandidates(100);
        }
        if (request.getMaxCandidates() < 1 || request.getMaxCandidates() > 500) {
            throw new BusinessException(400, "maxCandidates 必须在 1~500 之间");
        }
        // 精排候选上限：留空表示使用参数管理 rerank.maxDocs
        if (request.getRerankMaxDocs() != null
                && (request.getRerankMaxDocs() < 1 || request.getRerankMaxDocs() > 300)) {
            throw new BusinessException(400, "rerankMaxDocs 必须在 1~300 之间");
        }
        if (request.getTopN() == null) {
            request.setTopN(request.getTopK());
        }
        if (request.getTopN() < 1 || request.getTopN() > 50) {
            throw new BusinessException(400, "topN 必须在 1~50 之间");
        }

        if (request.getThreshold() == null) {
            request.setThreshold(0.3);
        }
        if (request.getThreshold() < 0 || request.getThreshold() > 1) {
            throw new BusinessException(400, "threshold 必须在 0~1 之间");
        }

        if ("hybrid".equals(request.getStrategy())) {
            if (request.getRrfK() == null) {
                request.setRrfK(60);
            }
            if (request.getRrfK() < 1 || request.getRrfK() > 1000) {
                throw new BusinessException(400, "rrfK 必须在 1~1000 之间");
            }
        }

        // Rerank 校验与策略无关：hybrid / vector / bm25 均可挂载
        if (request.isEnableRerank()) {
            if (request.getRerankThreshold() == null) {
                request.setRerankThreshold(
                        paramValueProvider.getDouble("rerank.minScore",
                                thresholdsConfig.getReranker().getMinScore()));
            }
            if (request.getRerankThreshold() < -100 || request.getRerankThreshold() > 100) {
                throw new BusinessException(400, "rerankThreshold 非法（Cross-Encoder 原始 logits，通常 -20~20 之间）");
            }
        }
    }
}