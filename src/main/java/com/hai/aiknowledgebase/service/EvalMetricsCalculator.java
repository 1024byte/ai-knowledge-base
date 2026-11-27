package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.RecallTestResponse;
import com.hai.aiknowledgebase.dto.eval.EvalMetricsSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索评测指标计算器（纯函数，便于单测）。
 *
 * <h3>口径（详见 docs/EVALUATION_BACKEND_DESIGN.md §6）</h3>
 * <ul>
 *   <li>相关性为二值：检索结果的 documentId 命中标注集合即为相关</li>
 *   <li>首个命中排名按 <b>chunk 位置</b>（1 起）</li>
 *   <li>Recall / Precision 按<b>文档去重</b>统计，Precision 分母取实际返回的去重文档数（≤K）</li>
 *   <li>NDCG 使用二值增益，DCG 按 chunk 位置，IDCG 取 min(标注数, K)</li>
 *   <li>实际返回不足 K 时按实际长度计算（等价于超出部分 rel=0）</li>
 *   <li>聚合只统计成功 Case</li>
 * </ul>
 */
public final class EvalMetricsCalculator {

    public static final List<Integer> KS = List.of(1, 3, 5, 10);

    private EvalMetricsCalculator() {
    }

    /** 单 Case 指标（全 K 保留在内存中用于聚合，落库只取部分字段）。 */
    public record CaseMetrics(
            Integer firstHitRank,
            Map<String, Boolean> hitAtK,
            Map<String, Double> recallAtK,
            Map<String, Double> precisionAtK,
            Map<String, Double> ndcgAtK,
            double mrr,
            List<Long> retrievedDocumentIds,
            List<Double> retrievedScores) {

        public double recallAt(int k) {
            return recallAtK.getOrDefault(String.valueOf(k), 0.0);
        }

        public double ndcgAt(int k) {
            return ndcgAtK.getOrDefault(String.valueOf(k), 0.0);
        }
    }

    /** 计算单条 Case 的检索指标。 */
    public static CaseMetrics caseMetrics(RecallTestResponse response, List<Long> relevantDocumentIds) {
        List<RecallTestResponse.RecallResultItem> items =
                response != null && response.getResults() != null && response.getResults().getFinalResults() != null
                        ? response.getResults().getFinalResults()
                        : List.of();

        Set<Long> relevant = relevantDocumentIds == null ? Set.of() : new LinkedHashSet<>(relevantDocumentIds);

        List<Long> docSequence = new ArrayList<>(items.size());
        List<Double> scores = new ArrayList<>(items.size());
        for (RecallTestResponse.RecallResultItem item : items) {
            docSequence.add(item.getDocumentId());
            scores.add(item.getScore());
        }

        Integer firstHitRank = null;
        for (int i = 0; i < docSequence.size(); i++) {
            Long docId = docSequence.get(i);
            if (docId != null && relevant.contains(docId)) {
                firstHitRank = i + 1;
                break;
            }
        }

        Map<String, Boolean> hitAtK = new HashMap<>();
        Map<String, Double> recallAtK = new HashMap<>();
        Map<String, Double> precisionAtK = new HashMap<>();
        Map<String, Double> ndcgAtK = new HashMap<>();
        for (int k : KS) {
            int limit = Math.min(k, docSequence.size());
            Set<Long> uniqueDocs = new LinkedHashSet<>();
            Set<Long> uniqueRelevant = new LinkedHashSet<>();
            double dcg = 0.0;
            for (int i = 0; i < limit; i++) {
                Long docId = docSequence.get(i);
                if (docId == null) {
                    continue;
                }
                uniqueDocs.add(docId);
                if (relevant.contains(docId)) {
                    uniqueRelevant.add(docId);
                    dcg += 1.0 / log2(i + 2.0);
                }
            }
            hitAtK.put(String.valueOf(k), !uniqueRelevant.isEmpty());
            recallAtK.put(String.valueOf(k), relevant.isEmpty() ? 0.0 : round4((double) uniqueRelevant.size() / relevant.size()));
            precisionAtK.put(String.valueOf(k), uniqueDocs.isEmpty() ? 0.0 : round4((double) uniqueRelevant.size() / uniqueDocs.size()));

            double idcg = 0.0;
            int idealLength = Math.min(relevant.size(), k);
            for (int i = 0; i < idealLength; i++) {
                idcg += 1.0 / log2(i + 2.0);
            }
            ndcgAtK.put(String.valueOf(k), idcg == 0.0 ? 0.0 : round4(dcg / idcg));
        }

        double mrr = firstHitRank == null ? 0.0 : round4(1.0 / firstHitRank);
        return new CaseMetrics(firstHitRank, hitAtK, recallAtK, precisionAtK, ndcgAtK, mrr, docSequence, scores);
    }

    /**
     * 聚合成功 Case 的汇总指标。
     *
     * @param successful         成功 Case 的指标列表
     * @param totalMsList        成功 Case 的耗时（与 successful 等长）
     * @param rewriteAppliedCount 执行了查询改写的 Case 数
     * @param failedCount        失败 Case 数
     */
    public static EvalMetricsSummary aggregate(List<CaseMetrics> successful, List<Long> totalMsList,
                                               long rewriteAppliedCount, int failedCount) {
        int n = successful.size();
        if (n == 0) {
            return null;
        }
        Map<String, Double> hitRate = new HashMap<>();
        Map<String, Double> recall = new HashMap<>();
        Map<String, Double> precision = new HashMap<>();
        Map<String, Double> ndcg = new HashMap<>();
        for (int k : KS) {
            String key = String.valueOf(k);
            double hit = 0, rec = 0, prec = 0;
            for (CaseMetrics m : successful) {
                hit += Boolean.TRUE.equals(m.hitAtK().get(key)) ? 1 : 0;
                rec += m.recallAtK().getOrDefault(key, 0.0);
                prec += m.precisionAtK().getOrDefault(key, 0.0);
            }
            hitRate.put(key, round4(hit / n));
            recall.put(key, round4(rec / n));
            precision.put(key, round4(prec / n));
        }
        double mrrSum = 0, ndcgSum = 0;
        for (CaseMetrics m : successful) {
            mrrSum += m.mrr();
            ndcgSum += m.ndcgAt(10);
        }
        ndcg.put("10", round4(ndcgSum / n));

        List<Long> sorted = new ArrayList<>(totalMsList);
        sorted.sort(Long::compareTo);
        long total = 0;
        for (Long ms : sorted) {
            total += ms == null ? 0 : ms;
        }

        return EvalMetricsSummary.builder()
                .hitRate(hitRate)
                .recall(recall)
                .precision(precision)
                .mrr(round4(mrrSum / n))
                .ndcg(ndcg)
                .avgMs(Math.round((double) total / n))
                .p50Ms(percentile(sorted, 0.50))
                .p95Ms(percentile(sorted, 0.95))
                .rewriteAppliedRate(round4((double) rewriteAppliedCount / n))
                .successCount(n)
                .failedCount(failedCount)
                .build();
    }

    /** nearest-rank 百分位：sorted[ceil(p*N)-1]。 */
    private static Long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
