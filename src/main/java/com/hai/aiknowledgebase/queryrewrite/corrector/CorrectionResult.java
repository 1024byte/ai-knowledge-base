package com.hai.aiknowledgebase.queryrewrite.corrector;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 纠错结果 DTO
 */
@Data
@Builder
public class CorrectionResult {

    /** 原始查询 */
    private String originalQuery;

    /** 纠错后的查询 */
    private String correctedQuery;

    /** 置信度 0.0~1.0 */
    private double confidence;

    /** 纠错层标识（L1/L2/L3） */
    private String layer;

    /** 是否发生了纠错 */
    private boolean corrected;

    /** 耗时（毫秒） */
    private long costMs;

    /** 纠错明细列表 */
    @Builder.Default
    private List<CorrectionDetail> details = Collections.emptyList();

    /**
     * 创建"无需纠错"的结果
     */
    public static CorrectionResult noCorrection(String query, String layer) {
        return CorrectionResult.builder()
                .originalQuery(query)
                .correctedQuery(query)
                .confidence(1.0)
                .layer(layer)
                .corrected(false)
                .costMs(0)
                .details(Collections.emptyList())
                .build();
    }
}