package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.RecallTestResponse;
import com.hai.aiknowledgebase.dto.eval.EvalRunConfig;
import com.hai.aiknowledgebase.entity.EvalCase;
import com.hai.aiknowledgebase.entity.EvalRun;
import com.hai.aiknowledgebase.entity.EvalRunCase;
import com.hai.aiknowledgebase.mapper.EvalCaseMapper;
import com.hai.aiknowledgebase.mapper.EvalRunCaseMapper;
import com.hai.aiknowledgebase.mapper.EvalRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvalRunExecutorTest {

    @Mock
    private EvalRunMapper runMapper;
    @Mock
    private EvalCaseMapper caseMapper;
    @Mock
    private EvalRunCaseMapper runCaseMapper;
    @Mock
    private RecallTestService recallTestService;
    @Mock
    private ThreadPoolTaskExecutor taskExecutor;

    private EvalRunExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new EvalRunExecutor(runMapper, caseMapper, runCaseMapper, recallTestService, taskExecutor);
    }

    @Test
    void successfulRunPersistsCaseAndSummary() {
        stubRun(List.of(evalCase(11L, "问题一")));
        when(recallTestService.run(any(), anyMap())).thenReturn(response(1L));

        executor.execute(1L);

        ArgumentCaptor<EvalRunCase> caseCaptor = ArgumentCaptor.forClass(EvalRunCase.class);
        verify(runCaseMapper).insert(caseCaptor.capture());
        assertThat(caseCaptor.getValue().getFirstHitRank()).isEqualTo(1);
        assertThat(caseCaptor.getValue().getResultSnapshot()).isNotNull();
        assertThat(caseCaptor.getValue().getRecallAt5()).isEqualTo(1.0);

        ArgumentCaptor<EvalRun> runCaptor = ArgumentCaptor.forClass(EvalRun.class);
        verify(runMapper, times(2)).update(runCaptor.capture(), any());
        EvalRun finished = runCaptor.getAllValues().get(1);
        assertThat(finished.getState()).isEqualTo("SUCCESS");
        assertThat(finished.getMetricsSummary().getSuccessCount()).isEqualTo(1);
        assertThat(finished.getHitRateAt5()).isEqualTo(1.0);
        assertThat(finished.getAvgMs()).isEqualTo(123L);
    }

    @Test
    void singleCaseFailureDoesNotInterruptRun() {
        stubRun(List.of(evalCase(11L, "问题一"), evalCase(12L, "问题二")));
        when(recallTestService.run(any(), anyMap()))
                .thenReturn(response(1L))
                .thenThrow(new IllegalStateException("检索失败"));

        executor.execute(1L);

        ArgumentCaptor<EvalRunCase> caseCaptor = ArgumentCaptor.forClass(EvalRunCase.class);
        verify(runCaseMapper, times(2)).insert(caseCaptor.capture());
        assertThat(caseCaptor.getAllValues().get(0).getErrorMessage()).isNull();
        assertThat(caseCaptor.getAllValues().get(1).getErrorMessage()).isEqualTo("检索失败");
        assertThat(caseCaptor.getAllValues().get(1).getResultSnapshot()).isNull();

        ArgumentCaptor<EvalRun> runCaptor = ArgumentCaptor.forClass(EvalRun.class);
        verify(runMapper, times(2)).update(runCaptor.capture(), any());
        EvalRun finished = runCaptor.getAllValues().get(1);
        assertThat(finished.getState()).isEqualTo("SUCCESS");
        assertThat(finished.getMetricsSummary().getSuccessCount()).isEqualTo(1);
        assertThat(finished.getMetricsSummary().getFailedCount()).isEqualTo(1);

        ArgumentCaptor<EvalRun> progressCaptor = ArgumentCaptor.forClass(EvalRun.class);
        verify(runMapper, times(2)).updateById(progressCaptor.capture());
        EvalRun lastProgress = progressCaptor.getAllValues().get(1);
        assertThat(lastProgress.getProgress()).isEqualTo(2);
        assertThat(lastProgress.getSuccessCount()).isEqualTo(1);
        assertThat(lastProgress.getFailedCount()).isEqualTo(1);
    }

    @Test
    void cancelFinishesCurrentCaseAndSkipsRemainingCases() {
        stubRun(List.of(evalCase(11L, "问题一"), evalCase(12L, "问题二")));
        when(recallTestService.run(any(), anyMap())).thenAnswer(invocation -> {
            executor.requestCancel(1L);
            return response(1L);
        });

        executor.execute(1L);

        verify(recallTestService, times(1)).run(any(), anyMap());
        verify(runCaseMapper, times(1)).insert(any(EvalRunCase.class));
        ArgumentCaptor<EvalRun> runCaptor = ArgumentCaptor.forClass(EvalRun.class);
        verify(runMapper, times(2)).updateById(runCaptor.capture());
        EvalRun canceled = runCaptor.getAllValues().get(1);
        assertThat(canceled.getState()).isEqualTo("CANCELED");
        assertThat(canceled.getMetricsSummary().getSuccessCount()).isEqualTo(1);
        assertThat(canceled.getFinishedAt()).isNotNull();
    }

    private void stubRun(List<EvalCase> cases) {
        EvalRun run = EvalRun.builder()
                .id(1L)
                .datasetId(2L)
                .kbId(3L)
                .state("PENDING")
                .totalCases(cases.size())
                .configSnapshot(EvalRunConfig.builder()
                        .strategy("hybrid")
                        .topK(5)
                        .topN(5)
                        .threshold(0.3)
                        .rrfK(60)
                        .enableRerank(false)
                        .enableRewrite(false)
                        .candidateMultiplier(3)
                        .maxCandidates(100)
                        .build())
                .paramSnapshot(Map.of("rerank.minScore", "-4.5"))
                .build();
        when(runMapper.selectById(1L)).thenReturn(run);
        when(runMapper.update(any(EvalRun.class), any())).thenReturn(1);
        when(runMapper.updateById(any(EvalRun.class))).thenReturn(1);
        when(caseMapper.selectList(any())).thenReturn(cases);
        when(runCaseMapper.insert(any(EvalRunCase.class))).thenReturn(1);
    }

    private EvalCase evalCase(Long id, String query) {
        return EvalCase.builder()
                .id(id)
                .datasetId(2L)
                .query(query)
                .relevantDocumentIds(List.of(1L))
                .tags(List.of("tag"))
                .sortOrder(id.intValue())
                .isDeleted(0)
                .build();
    }

    private RecallTestResponse response(Long documentId) {
        return RecallTestResponse.builder()
                .totalMs(123L)
                .rewriteApplied(true)
                .rewritePath("LLM_REWRITE")
                .rewrittenQuery("改写后问题")
                .results(RecallTestResponse.Results.builder()
                        .finalResults(List.of(RecallTestResponse.RecallResultItem.builder()
                                .documentId(documentId)
                                .score(0.9)
                                .build()))
                        .build())
                .build();
    }
}
