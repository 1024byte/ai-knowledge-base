package com.hai.aiknowledgebase.dto.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 评测运行汇总指标。
 *
 * <p>仅统计成功 Case；K 集合固定为 {"1","3","5","10"}，NDCG 只输出 @10。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalMetricsSummary {

    private Map<String, Double> hitRate;
    private Map<String, Double> recall;
    private Map<String, Double> precision;
    private Double mrr;
    private Map<String, Double> ndcg;

    /** 成功 Case 平均耗时（含改写） */
    private Long avgMs;
    private Long p50Ms;
    private Long p95Ms;

    /** 执行了查询改写的 Case 占比 */
    private Double rewriteAppliedRate;

    private Integer successCount;
    private Integer failedCount;
}
