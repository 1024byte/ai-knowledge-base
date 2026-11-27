package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hai.aiknowledgebase.dto.RecallTestRequest;
import com.hai.aiknowledgebase.dto.RecallTestResponse;
import com.hai.aiknowledgebase.dto.eval.EvalMetricsSummary;
import com.hai.aiknowledgebase.dto.eval.EvalRunConfig;
import com.hai.aiknowledgebase.entity.EvalCase;
import com.hai.aiknowledgebase.entity.EvalRun;
import com.hai.aiknowledgebase.entity.EvalRunCase;
import com.hai.aiknowledgebase.mapper.EvalCaseMapper;
import com.hai.aiknowledgebase.mapper.EvalRunCaseMapper;
import com.hai.aiknowledgebase.mapper.EvalRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 串行执行评测 Run，并逐条持久化 Case 结果。 */
@Slf4j
@Service
public class EvalRunExecutor {

    private final EvalRunMapper runMapper;
    private final EvalCaseMapper caseMapper;
    private final EvalRunCaseMapper runCaseMapper;
    private final RecallTestService recallTestService;
    private final ThreadPoolTaskExecutor taskExecutor;

    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public EvalRunExecutor(EvalRunMapper runMapper,
                           EvalCaseMapper caseMapper,
                           EvalRunCaseMapper runCaseMapper,
                           RecallTestService recallTestService,
                           @Qualifier("evalTaskExecutor") ThreadPoolTaskExecutor taskExecutor) {
        this.runMapper = runMapper;
        this.caseMapper = caseMapper;
        this.runCaseMapper = runCaseMapper;
        this.recallTestService = recallTestService;
        this.taskExecutor = taskExecutor;
    }

    public void submit(Long runId) {
        taskExecutor.execute(() -> execute(runId));
    }

    public void requestCancel(Long runId) {
        cancelFlags.computeIfAbsent(runId, ignored -> new AtomicBoolean()).set(true);
    }

    /** 供线程池任务及单元测试直接调用。 */
    public void execute(Long runId) {
        AtomicBoolean cancelFlag = cancelFlags.computeIfAbsent(runId, ignored -> new AtomicBoolean());
        long executionStart = System.currentTimeMillis();
        try {
            EvalRun run = runMapper.selectById(runId);
            if (run == null || "CANCELED".equals(run.getState())) {
                return;
            }
            if (!"PENDING".equals(run.getState()) && !"RUNNING".equals(run.getState())) {
                return;
            }

            LocalDateTime startedAt = run.getStartedAt() == null ? LocalDateTime.now() : run.getStartedAt();
            if ("PENDING".equals(run.getState())) {
                EvalRun running = new EvalRun();
                running.setId(runId);
                running.setState("RUNNING");
                running.setStartedAt(startedAt);
                int updated = runMapper.update(running, new LambdaUpdateWrapper<EvalRun>()
                        .eq(EvalRun::getId, runId)
                        .eq(EvalRun::getState, "PENDING"));
                if (updated == 0) {
                    return;
                }
            }

            List<EvalCase> cases = caseMapper.selectList(new LambdaQueryWrapper<EvalCase>()
                    .eq(EvalCase::getDatasetId, run.getDatasetId())
                    .eq(EvalCase::getIsDeleted, 0)
                    .orderByAsc(EvalCase::getSortOrder)
                    .orderByAsc(EvalCase::getId));

            List<EvalMetricsCalculator.CaseMetrics> successfulMetrics = new ArrayList<>();
            List<Long> successfulTimes = new ArrayList<>();
            long rewriteAppliedCount = 0;
            int successCount = 0;
            int failedCount = 0;
            int progress = 0;

            for (EvalCase evalCase : cases) {
                if (isCanceled(runId, cancelFlag)) {
                    break;
                }
                try {
                    RecallTestResponse response = recallTestService.run(
                            buildRequest(run, evalCase), run.getParamSnapshot());
                    EvalMetricsCalculator.CaseMetrics metrics = EvalMetricsCalculator.caseMetrics(
                            response, evalCase.getRelevantDocumentIds());
                    long totalMs = response.getTotalMs() == null ? 0L : response.getTotalMs();
                    runCaseMapper.insert(EvalRunCase.builder()
                            .runId(runId)
                            .caseId(evalCase.getId())
                            .query(evalCase.getQuery())
                            .tags(copy(evalCase.getTags()))
                            .relevantDocumentIds(evalCase.getRelevantDocumentIds())
                            .sortOrder(evalCase.getSortOrder())
                            .firstHitRank(metrics.firstHitRank())
                            .hitAtK(metrics.hitAtK())
                            .recallAt5(metrics.recallAt(5))
                            .ndcgAt10(metrics.ndcgAt(10))
                            .mrr(metrics.mrr())
                            .totalMs(totalMs)
                            .rewritePath(response.getRewritePath())
                            .rewrittenQuery(response.getRewrittenQuery())
                            .retrievedDocumentIds(metrics.retrievedDocumentIds())
                            .retrievedScores(metrics.retrievedScores())
                            .resultSnapshot(response)
                            .createdAt(LocalDateTime.now())
                            .build());
                    successfulMetrics.add(metrics);
                    successfulTimes.add(totalMs);
                    if (Boolean.TRUE.equals(response.getRewriteApplied())) {
                        rewriteAppliedCount++;
                    }
                    successCount++;
                } catch (Exception e) {
                    log.warn("评测 Case 失败: runId={}, caseId={}", runId, evalCase.getId(), e);
                    runCaseMapper.insert(EvalRunCase.builder()
                            .runId(runId)
                            .caseId(evalCase.getId())
                            .query(evalCase.getQuery())
                            .tags(copy(evalCase.getTags()))
                            .relevantDocumentIds(evalCase.getRelevantDocumentIds())
                            .sortOrder(evalCase.getSortOrder())
                            .errorMessage(errorMessage(e))
                            .createdAt(LocalDateTime.now())
                            .build());
                    failedCount++;
                }
                progress++;
                updateProgress(runId, progress, successCount, failedCount);
            }

            EvalMetricsSummary summary = EvalMetricsCalculator.aggregate(
                    successfulMetrics, successfulTimes, rewriteAppliedCount, failedCount);
            boolean canceled = isCanceled(runId, cancelFlag);
            LocalDateTime finishedAt = LocalDateTime.now();
            EvalRun finished = new EvalRun();
            finished.setId(runId);
            finished.setState(canceled ? "CANCELED" : "SUCCESS");
            finished.setMetricsSummary(summary);
            finished.setHitRateAt5(summary == null ? null : summary.getHitRate().get("5"));
            finished.setAvgMs(summary == null ? null : summary.getAvgMs());
            finished.setTotalMs(System.currentTimeMillis() - executionStart);
            finished.setFinishedAt(finishedAt);
            if (canceled) {
                runMapper.updateById(finished);
            } else {
                runMapper.update(finished, new LambdaUpdateWrapper<EvalRun>()
                        .eq(EvalRun::getId, runId)
                        .ne(EvalRun::getState, "CANCELED"));
            }
            log.info("评测运行完成: runId={}, state={}, success={}, failed={}",
                    runId, finished.getState(), successCount, failedCount);
        } catch (Exception e) {
            log.error("评测运行失败: runId={}", runId, e);
            EvalRun failed = new EvalRun();
            failed.setId(runId);
            failed.setState("FAILED");
            failed.setErrorMessage(errorMessage(e));
            failed.setTotalMs(System.currentTimeMillis() - executionStart);
            failed.setFinishedAt(LocalDateTime.now());
            runMapper.update(failed, new LambdaUpdateWrapper<EvalRun>()
                    .eq(EvalRun::getId, runId)
                    .ne(EvalRun::getState, "CANCELED"));
        } finally {
            cancelFlags.remove(runId);
        }
    }

    private RecallTestRequest buildRequest(EvalRun run, EvalCase evalCase) {
        EvalRunConfig config = run.getConfigSnapshot();
        return RecallTestRequest.builder()
                .query(evalCase.getQuery())
                .kbIds(List.of(run.getKbId()))
                .documentIds(List.of())
                .strategy(config.getStrategy())
                .topK(config.getTopK())
                .threshold(config.getThreshold())
                .rrfK(config.getRrfK())
                .enableRerank(Boolean.TRUE.equals(config.getEnableRerank()))
                .rerankStage(config.getRerankStage())
                .rerankThreshold(config.getRerankThreshold())
                .rerankMaxDocs(config.getRerankMaxDocs())
                .candidateMultiplier(config.getCandidateMultiplier())
                .maxCandidates(config.getMaxCandidates())
                .topN(config.getTopN())
                .enableRewrite(config.getEnableRewrite())
                .excludeKeywords(copy(config.getExcludeKeywords()))
                .build();
    }

    private boolean isCanceled(Long runId, AtomicBoolean cancelFlag) {
        if (cancelFlag.get()) {
            return true;
        }
        EvalRun current = runMapper.selectById(runId);
        return current == null || "CANCELED".equals(current.getState());
    }

    private void updateProgress(Long runId, int progress, int successCount, int failedCount) {
        EvalRun update = new EvalRun();
        update.setId(runId);
        update.setProgress(progress);
        update.setSuccessCount(successCount);
        update.setFailedCount(failedCount);
        runMapper.updateById(update);
    }

    private List<String> copy(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }
}
