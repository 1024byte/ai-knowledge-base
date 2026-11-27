package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.RecallTestResponse;
import com.hai.aiknowledgebase.dto.eval.EvalMetricsSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalMetricsCalculatorTest {

    @Test
    void calculatesHitRecallPrecisionMrrWithDuplicateChunks() {
        EvalMetricsCalculator.CaseMetrics metrics = EvalMetricsCalculator.caseMetrics(
                response(3L, 1L, 1L, 2L), List.of(1L, 2L));

        assertThat(metrics.firstHitRank()).isEqualTo(2);
        assertThat(metrics.hitAtK().get("1")).isFalse();
        assertThat(metrics.hitAtK().get("3")).isTrue();
        assertThat(metrics.recallAt(3)).isEqualTo(0.5);
        assertThat(metrics.recallAt(5)).isEqualTo(1.0);
        assertThat(metrics.precisionAtK().get("3")).isEqualTo(0.5);
        assertThat(metrics.precisionAtK().get("5")).isEqualTo(0.6667);
        assertThat(metrics.mrr()).isEqualTo(0.5);
        assertThat(metrics.ndcgAt(10)).isBetween(0.0, 1.0);
        assertThat(metrics.retrievedScores()).containsExactly(3.0, 1.0, 1.0, 2.0);
    }

    @Test
    void returnedResultsShorterThanKUseActualLength() {
        EvalMetricsCalculator.CaseMetrics metrics = EvalMetricsCalculator.caseMetrics(
                response(20L, 30L), List.of(4L));

        assertThat(metrics.firstHitRank()).isNull();
        assertThat(metrics.hitAtK().get("10")).isFalse();
        assertThat(metrics.recallAt(10)).isZero();
        assertThat(metrics.precisionAtK().get("10")).isZero();
        assertThat(metrics.mrr()).isZero();
    }

    @Test
    void emptyRelevantSetProducesZeroNdcg() {
        EvalMetricsCalculator.CaseMetrics metrics = EvalMetricsCalculator.caseMetrics(
                response(1L, 2L), List.of());

        assertThat(metrics.ndcgAt(10)).isZero();
        assertThat(metrics.recallAt(10)).isZero();
        assertThat(metrics.firstHitRank()).isNull();
    }

    @Test
    void emptyResultsProduceZeroMetrics() {
        EvalMetricsCalculator.CaseMetrics metrics = EvalMetricsCalculator.caseMetrics(
                RecallTestResponse.builder().results(RecallTestResponse.Results.builder().build()).build(),
                List.of(1L));

        assertThat(metrics.firstHitRank()).isNull();
        assertThat(metrics.hitAtK().values()).containsOnly(false);
        assertThat(metrics.mrr()).isZero();
        assertThat(metrics.retrievedDocumentIds()).isEmpty();
    }

    @Test
    void aggregatesOnlySuccessfulCasesAndUsesNearestRankPercentiles() {
        EvalMetricsCalculator.CaseMetrics first = EvalMetricsCalculator.caseMetrics(
                response(1L), List.of(1L));
        EvalMetricsCalculator.CaseMetrics second = EvalMetricsCalculator.caseMetrics(
                response(2L, 1L), List.of(1L));

        EvalMetricsSummary summary = EvalMetricsCalculator.aggregate(
                List.of(first, second), List.of(100L, 300L), 1, 2);

        assertThat(summary.getHitRate().get("1")).isEqualTo(0.5);
        assertThat(summary.getMrr()).isEqualTo(0.75);
        assertThat(summary.getAvgMs()).isEqualTo(200L);
        assertThat(summary.getP50Ms()).isEqualTo(100L);
        assertThat(summary.getP95Ms()).isEqualTo(300L);
        assertThat(summary.getRewriteAppliedRate()).isEqualTo(0.5);
        assertThat(summary.getSuccessCount()).isEqualTo(2);
        assertThat(summary.getFailedCount()).isEqualTo(2);
    }

    @Test
    void aggregateReturnsNullWhenNoCaseSucceeded() {
        assertThat(EvalMetricsCalculator.aggregate(List.of(), List.of(), 0, 3)).isNull();
    }

    private RecallTestResponse response(Long... documentIds) {
        List<RecallTestResponse.RecallResultItem> items = new ArrayList<>();
        for (Long documentId : documentIds) {
            items.add(RecallTestResponse.RecallResultItem.builder()
                    .documentId(documentId)
                    .score((double) documentId)
                    .build());
        }
        return RecallTestResponse.builder()
                .totalMs(123L)
                .results(RecallTestResponse.Results.builder().finalResults(items).build())
                .build();
    }
}
