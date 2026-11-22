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

        if (request.getTopK() == null) {
            request.setTopK(5);
        }
        if (request.getTopK() < 1 || request.getTopK() > 50) {
            throw new BusinessException(400, "topK 必须在 1~50 之间");
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

            if (request.isEnableRerank()) {
                if (request.getRerankThreshold() == null) {
                    Long rep = request.getKbIds().isEmpty()
                            ? null
                            : Collections.min(request.getKbIds());
                    request.setRerankThreshold(
                            paramValueProvider.getDouble("rerank.minScore", rep,
                                    thresholdsConfig.getReranker().getMinScore()));
                }
                if (request.getRerankThreshold() < -100 || request.getRerankThreshold() > 100) {
                    throw new BusinessException(400, "rerankThreshold 非法（Cross-Encoder 原始 logits，通常 -20~20 之间）");
                }
            }
        }
    }
}