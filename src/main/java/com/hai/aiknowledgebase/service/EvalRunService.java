package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hai.aiknowledgebase.common.Role;
import com.hai.aiknowledgebase.config.ThresholdsConfig;
import com.hai.aiknowledgebase.dto.eval.EvalRunCaseDetailVO;
import com.hai.aiknowledgebase.dto.eval.EvalRunCaseVO;
import com.hai.aiknowledgebase.dto.eval.EvalRunConfig;
import com.hai.aiknowledgebase.dto.eval.EvalRunCreateRequest;
import com.hai.aiknowledgebase.dto.eval.EvalRunDetailVO;
import com.hai.aiknowledgebase.dto.eval.EvalRunVO;
import com.hai.aiknowledgebase.entity.EvalCase;
import com.hai.aiknowledgebase.entity.EvalDataset;
import com.hai.aiknowledgebase.entity.EvalRun;
import com.hai.aiknowledgebase.entity.EvalRunCase;
import com.hai.aiknowledgebase.entity.KnowledgeBase;
import com.hai.aiknowledgebase.entity.User;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.EvalCaseMapper;
import com.hai.aiknowledgebase.mapper.EvalDatasetMapper;
import com.hai.aiknowledgebase.mapper.EvalRunCaseMapper;
import com.hai.aiknowledgebase.mapper.EvalRunMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeBaseMapper;
import com.hai.aiknowledgebase.mapper.UserMapper;
import com.hai.aiknowledgebase.mapper.handler.JsonbTypeHandler;
import com.hai.aiknowledgebase.model.ModelConfigService;
import com.hai.aiknowledgebase.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 评测运行创建、查询、取消、删除与导出。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalRunService {

    private static final Set<String> STRATEGIES = Set.of("hybrid", "vector", "bm25");
    private static final Set<String> RERANK_STAGES = Set.of("fuse_rerank", "rerank_fuse");
    private static final Set<String> RUN_STATES = Set.of("PENDING", "RUNNING", "SUCCESS", "FAILED", "CANCELED");
    private static final int MAX_CASES = 500;

    private final EvalRunMapper runMapper;
    private final EvalRunCaseMapper runCaseMapper;
    private final EvalDatasetMapper datasetMapper;
    private final EvalCaseMapper caseMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final UserMapper userMapper;
    private final KbPermissionService kbPermissionService;
    private final ParamService paramService;
    private final ParamValueProvider paramValueProvider;
    private final ThresholdsConfig thresholdsConfig;
    private final EvalRunExecutor runExecutor;
    private final ModelConfigService modelConfigService;

    private final ObjectMapper objectMapper = JsonbTypeHandler.mapper();

    public Long create(EvalRunCreateRequest request) {
        if (request == null || request.getDatasetId() == null) {
            throw new BusinessException(400, "参数校验失败：datasetId 不能为空");
        }
        EvalDataset dataset = requireDataset(request.getDatasetId());
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException(400, "参数校验失败：name 不能为空");
        }
        List<EvalCase> cases = caseMapper.selectList(new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getDatasetId, dataset.getId())
                .eq(EvalCase::getIsDeleted, 0)
                .orderByAsc(EvalCase::getSortOrder)
                .orderByAsc(EvalCase::getId));
        if (cases.isEmpty()) {
            throw new BusinessException(400, "评测集没有可运行的 Case");
        }
        if (cases.size() > MAX_CASES) {
            throw new BusinessException(400, "单次运行最多 " + MAX_CASES + " 条 Case");
        }
        ensureNoActiveRun();

        EvalRunConfig config = normalizeConfig(request);
        EvalRun run = EvalRun.builder()
                .datasetId(dataset.getId())
                .kbId(dataset.getKbId())
                .name(request.getName().trim())
                .state("PENDING")
                .progress(0)
                .totalCases(cases.size())
                .successCount(0)
                .failedCount(0)
                .configSnapshot(config)
                .paramSnapshot(buildParamSnapshot())
                .createdBy(SecurityUtils.getCurrentUserId())
                .createdByName(SecurityUtils.getCurrentUsername())
                .createdAt(LocalDateTime.now())
                .build();
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException e) {
            throw activeRunError();
        }

        try {
            runExecutor.submit(run.getId());
        } catch (TaskRejectedException e) {
            markStartFailed(run.getId(), "评测任务提交被拒绝");
            throw activeRunError();
        } catch (RuntimeException e) {
            markStartFailed(run.getId(), errorMessage(e));
            throw new BusinessException(500, "评测启动失败: " + errorMessage(e));
        }
        return run.getId();
    }

    /**
     * 构建评测参数快照：检索参数 + 当前生效的模型配置 ID。
     *
     * <p>加入 {@code model.*.id} 伪参数后，运行期间的模型选择被冻结，
     * 避免同一批 Case 横跨两套模型导致指标不可比。</p>
     */
    private Map<String, String> buildParamSnapshot() {
        Map<String, String> snapshot = new HashMap<>(paramService.resolveAll(null));
        modelConfigService.activeIdSnapshot()
                .forEach((type, id) -> snapshot.put("model." + type + ".id", id));
        return snapshot;
    }

    public Map<String, Object> list(Long datasetId, Long kbId, String state,
                                    LocalDateTime startAt, LocalDateTime endAt,
                                    Integer page, Integer size) {
        int pageNum = normalizePage(page);
        int pageSize = normalizeSize(size, 20);
        if (startAt != null && endAt != null && startAt.isAfter(endAt)) {
            throw new BusinessException(400, "startAt 不能晚于 endAt");
        }
        Set<Long> visibleKbIds = new LinkedHashSet<>(kbPermissionService.getVisibleKbIds(
                SecurityUtils.getCurrentUserId()));
        if (visibleKbIds.isEmpty() || (kbId != null && !visibleKbIds.contains(kbId))) {
            return pageResult(0, List.of());
        }

        LambdaQueryWrapper<EvalRun> wrapper = new LambdaQueryWrapper<EvalRun>()
                .select(EvalRun::getId, EvalRun::getDatasetId, EvalRun::getKbId, EvalRun::getName,
                        EvalRun::getState, EvalRun::getProgress, EvalRun::getTotalCases,
                        EvalRun::getHitRateAt5, EvalRun::getAvgMs, EvalRun::getTotalMs,
                        EvalRun::getCreatedByName, EvalRun::getStartedAt, EvalRun::getFinishedAt,
                        EvalRun::getCreatedAt)
                .eq(datasetId != null, EvalRun::getDatasetId, datasetId)
                .ge(startAt != null, EvalRun::getCreatedAt, startAt)
                .le(endAt != null, EvalRun::getCreatedAt, endAt)
                .orderByDesc(EvalRun::getId);
        if (kbId != null) {
            wrapper.eq(EvalRun::getKbId, kbId);
        } else {
            wrapper.in(EvalRun::getKbId, visibleKbIds);
        }
        List<String> states = parseStates(state);
        if (!states.isEmpty()) {
            wrapper.in(EvalRun::getState, states);
        }

        Page<EvalRun> entityPage = runMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<EvalRun> runs = entityPage.getRecords();
        Map<Long, String> datasetNames = datasetNames(runs);
        Map<Long, String> kbNames = kbNames(runs);
        List<EvalRunVO> records = runs.stream()
                .map(run -> toVO(run, datasetNames.get(run.getDatasetId()), kbNames.get(run.getKbId())))
                .collect(Collectors.toList());
        return pageResult(entityPage.getTotal(), records);
    }

    public EvalRunDetailVO detail(Long id) {
        EvalRun run = requireRun(id);
        return toDetail(run, datasetName(run.getDatasetId()), kbName(run.getKbId()));
    }

    public void cancel(Long id) {
        requireRun(id);
        LocalDateTime now = LocalDateTime.now();
        int updated = runMapper.update(null, new LambdaUpdateWrapper<EvalRun>()
                .eq(EvalRun::getId, id)
                .in(EvalRun::getState, "PENDING", "RUNNING")
                .set(EvalRun::getState, "CANCELED")
                .set(EvalRun::getFinishedAt, now));
        if (updated == 0) {
            throw new BusinessException(400, "运行已结束，无法取消");
        }
        runExecutor.requestCancel(id);
    }

    @Transactional
    public void delete(Long id) {
        EvalRun run = requireRun(id);
        Long userId = SecurityUtils.getCurrentUserId();
        if (!Objects.equals(run.getCreatedBy(), userId) && !isGlobalAdmin(userId)) {
            throw new BusinessException(403, "仅运行创建人或管理员可删除");
        }
        if ("PENDING".equals(run.getState()) || "RUNNING".equals(run.getState())) {
            throw new BusinessException(400, "运行未结束，请先取消");
        }
        runCaseMapper.delete(new LambdaQueryWrapper<EvalRunCase>().eq(EvalRunCase::getRunId, id));
        runMapper.deleteById(id);
    }

    public Map<String, Object> cases(Long runId, Integer page, Integer size,
                                     Boolean onlyMissed, Boolean onlyFailed, String tag) {
        requireRun(runId);
        int pageNum = normalizePage(page);
        int pageSize = normalizeSize(size, 50);
        LambdaQueryWrapper<EvalRunCase> wrapper = caseQuery(runId);
        if (Boolean.TRUE.equals(onlyFailed)) {
            wrapper.isNotNull(EvalRunCase::getErrorMessage);
        }
        if (Boolean.TRUE.equals(onlyMissed)) {
            wrapper.isNull(EvalRunCase::getFirstHitRank).isNull(EvalRunCase::getErrorMessage);
        }
        addTagFilter(wrapper, tag);
        Page<EvalRunCase> entityPage = runCaseMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<EvalRunCaseVO> records = entityPage.getRecords().stream()
                .map(this::toCaseVO)
                .collect(Collectors.toList());
        return pageResult(entityPage.getTotal(), records);
    }

    public EvalRunCaseDetailVO caseDetail(Long runId, Long caseId) {
        requireRun(runId);
        EvalRunCase runCase = runCaseMapper.selectOne(new LambdaQueryWrapper<EvalRunCase>()
                .eq(EvalRunCase::getRunId, runId)
                .eq(EvalRunCase::getCaseId, caseId)
                .last("LIMIT 1"));
        if (runCase == null) {
            throw new BusinessException(400, "运行 Case 不存在");
        }
        return toCaseDetail(runCase);
    }

    public String exportJson(Long runId) {
        EvalRunDetailVO run = detail(runId);
        List<EvalRunCaseVO> cases = listAllCases(runId, null, null, null).stream()
                .map(this::toCaseVO)
                .collect(Collectors.toList());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run", run);
        payload.put("cases", cases);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            throw new BusinessException(500, "导出失败: " + errorMessage(e));
        }
    }

    public byte[] exportCsv(Long runId) {
        requireRun(runId);
        List<EvalRunCaseVO> cases = listAllCases(runId, null, null, null).stream()
                .map(this::toCaseVO)
                .toList();
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsvRow(csv, "caseId", "query", "tags", "relevantDocumentIds", "hit", "firstHitRank",
                "hit@1", "hit@3", "hit@5", "hit@10", "recall@5", "ndcg@10", "mrr", "totalMs",
                "rewritePath", "rewrittenQuery", "errorMessage");
        for (EvalRunCaseVO c : cases) {
            appendCsvRow(csv, c.getCaseId(), c.getQuery(), join(c.getTags()), join(c.getRelevantDocumentIds()),
                    c.getHit(), c.getFirstHitRank(), hitAt(c, "1"), hitAt(c, "3"), hitAt(c, "5"),
                    hitAt(c, "10"), c.getRecallAt5(), c.getNdcgAt10(), c.getMrr(), c.getTotalMs(),
                    c.getRewritePath(), c.getRewrittenQuery(), c.getErrorMessage());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private EvalRunConfig normalizeConfig(EvalRunCreateRequest request) {
        String strategy = request.getStrategy() == null || request.getStrategy().isBlank()
                ? "hybrid" : request.getStrategy();
        if (!STRATEGIES.contains(strategy)) {
            throw new BusinessException(400, "不支持的检索策略");
        }
        String rerankStage = request.getRerankStage() == null || request.getRerankStage().isBlank()
                ? "fuse_rerank" : request.getRerankStage();
        if (!RERANK_STAGES.contains(rerankStage)) {
            throw new BusinessException(400, "不支持的 rerankStage");
        }
        int topK = request.getTopK() == null ? 5 : request.getTopK();
        checkRange("topK", topK, 1, 50);
        int topN = request.getTopN() == null ? topK : request.getTopN();
        checkRange("topN", topN, 1, 50);
        int candidateMultiplier = request.getCandidateMultiplier() == null ? 3 : request.getCandidateMultiplier();
        checkRange("candidateMultiplier", candidateMultiplier, 1, 10);
        int maxCandidates = request.getMaxCandidates() == null ? 100 : request.getMaxCandidates();
        checkRange("maxCandidates", maxCandidates, 1, 500);
        if (request.getRerankMaxDocs() != null) {
            checkRange("rerankMaxDocs", request.getRerankMaxDocs(), 1, 300);
        }
        double threshold = request.getThreshold() == null ? 0.3 : request.getThreshold();
        if (threshold < 0 || threshold > 1) {
            throw new BusinessException(400, "threshold 必须在 0~1 之间");
        }
        int rrfK = request.getRrfK() == null ? 60 : request.getRrfK();
        checkRange("rrfK", rrfK, 1, 1000);
        boolean enableRerank = Boolean.TRUE.equals(request.getEnableRerank());
        double rerankThreshold = request.getRerankThreshold() == null
                ? paramValueProvider.getDouble("rerank.minScore", thresholdsConfig.getReranker().getMinScore())
                : request.getRerankThreshold();
        if (enableRerank && (rerankThreshold < -100 || rerankThreshold > 100)) {
            throw new BusinessException(400, "rerankThreshold 非法（Cross-Encoder 原始 logits，通常 -20~20 之间）");
        }
        boolean enableRewrite = request.getEnableRewrite() == null
                ? paramValueProvider.getBoolean("queryRewrite.enabled", false)
                : request.getEnableRewrite();
        return EvalRunConfig.builder()
                .strategy(strategy)
                .topK(topK)
                .threshold(threshold)
                .rrfK(rrfK)
                .enableRerank(enableRerank)
                .rerankStage(rerankStage)
                .rerankThreshold(rerankThreshold)
                .rerankMaxDocs(request.getRerankMaxDocs())
                .candidateMultiplier(candidateMultiplier)
                .maxCandidates(maxCandidates)
                .topN(topN)
                .enableRewrite(enableRewrite)
                .excludeKeywords(request.getExcludeKeywords() == null
                        ? List.of() : new ArrayList<>(request.getExcludeKeywords()))
                .build();
    }

    private void ensureNoActiveRun() {
        Long count = runMapper.selectCount(new LambdaQueryWrapper<EvalRun>()
                .in(EvalRun::getState, "PENDING", "RUNNING"));
        if (count != null && count > 0) {
            throw activeRunError();
        }
    }

    private BusinessException activeRunError() {
        return new BusinessException(400, "已有评测正在运行，请等待完成或先取消");
    }

    private void markStartFailed(Long runId, String message) {
        EvalRun failed = new EvalRun();
        failed.setId(runId);
        failed.setState("FAILED");
        failed.setErrorMessage(message);
        failed.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(failed);
    }

    private EvalDataset requireDataset(Long id) {
        EvalDataset dataset = datasetMapper.selectById(id);
        if (dataset == null || (dataset.getIsDeleted() != null && dataset.getIsDeleted() == 1)) {
            throw new BusinessException(400, "评测集不存在");
        }
        kbPermissionService.checkPermission(SecurityUtils.getCurrentUserId(), dataset.getKbId(), Role.VIEWER);
        return dataset;
    }

    private EvalRun requireRun(Long id) {
        EvalRun run = id == null ? null : runMapper.selectById(id);
        if (run == null) {
            throw new BusinessException(400, "评测运行不存在");
        }
        kbPermissionService.checkPermission(SecurityUtils.getCurrentUserId(), run.getKbId(), Role.VIEWER);
        return run;
    }

    private LambdaQueryWrapper<EvalRunCase> caseQuery(Long runId) {
        return new LambdaQueryWrapper<EvalRunCase>()
                .select(EvalRunCase::getId, EvalRunCase::getRunId, EvalRunCase::getCaseId,
                        EvalRunCase::getQuery, EvalRunCase::getTags, EvalRunCase::getRelevantDocumentIds,
                        EvalRunCase::getSortOrder, EvalRunCase::getFirstHitRank, EvalRunCase::getHitAtK,
                        EvalRunCase::getRecallAt5, EvalRunCase::getNdcgAt10, EvalRunCase::getMrr,
                        EvalRunCase::getTotalMs, EvalRunCase::getRewritePath, EvalRunCase::getRewrittenQuery,
                        EvalRunCase::getErrorMessage, EvalRunCase::getCreatedAt)
                .eq(EvalRunCase::getRunId, runId)
                .orderByAsc(EvalRunCase::getSortOrder)
                .orderByAsc(EvalRunCase::getId);
    }

    private List<EvalRunCase> listAllCases(Long runId, Boolean onlyMissed, Boolean onlyFailed, String tag) {
        LambdaQueryWrapper<EvalRunCase> wrapper = caseQuery(runId);
        if (Boolean.TRUE.equals(onlyFailed)) {
            wrapper.isNotNull(EvalRunCase::getErrorMessage);
        }
        if (Boolean.TRUE.equals(onlyMissed)) {
            wrapper.isNull(EvalRunCase::getFirstHitRank).isNull(EvalRunCase::getErrorMessage);
        }
        addTagFilter(wrapper, tag);
        return runCaseMapper.selectList(wrapper);
    }

    private void addTagFilter(LambdaQueryWrapper<EvalRunCase> wrapper, String tag) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        try {
            wrapper.apply("tags @> CAST({0} AS jsonb)", objectMapper.writeValueAsString(List.of(tag.trim())));
        } catch (Exception e) {
            throw new BusinessException(500, "标签筛选条件生成失败");
        }
    }

    private EvalRunVO toVO(EvalRun run, String datasetName, String kbName) {
        return EvalRunVO.builder()
                .id(run.getId())
                .datasetId(run.getDatasetId())
                .datasetName(datasetName)
                .kbId(run.getKbId())
                .kbName(kbName)
                .name(run.getName())
                .state(run.getState())
                .progress(run.getProgress())
                .totalCases(run.getTotalCases())
                .hitRateAt5(run.getHitRateAt5())
                .avgMs(run.getAvgMs())
                .totalMs(run.getTotalMs())
                .createdBy(run.getCreatedByName())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .createdAt(run.getCreatedAt())
                .build();
    }

    private EvalRunDetailVO toDetail(EvalRun run, String datasetName, String kbName) {
        return EvalRunDetailVO.builder()
                .id(run.getId())
                .datasetId(run.getDatasetId())
                .datasetName(datasetName)
                .kbId(run.getKbId())
                .kbName(kbName)
                .name(run.getName())
                .state(run.getState())
                .progress(run.getProgress())
                .totalCases(run.getTotalCases())
                .successCount(run.getSuccessCount())
                .failedCount(run.getFailedCount())
                .configSnapshot(run.getConfigSnapshot())
                .paramSnapshot(run.getParamSnapshot())
                .metricsSummary(run.getMetricsSummary())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .createdAt(run.getCreatedAt())
                .errorMessage(run.getErrorMessage())
                .build();
    }

    private EvalRunCaseVO toCaseVO(EvalRunCase c) {
        return EvalRunCaseVO.builder()
                .id(c.getId())
                .caseId(c.getCaseId())
                .query(c.getQuery())
                .tags(c.getTags())
                .relevantDocumentIds(c.getRelevantDocumentIds())
                .hit(c.getFirstHitRank() != null)
                .firstHitRank(c.getFirstHitRank())
                .hitAtK(c.getHitAtK())
                .recallAt5(c.getRecallAt5())
                .ndcgAt10(c.getNdcgAt10())
                .mrr(c.getMrr())
                .totalMs(c.getTotalMs())
                .rewritePath(c.getRewritePath())
                .rewrittenQuery(c.getRewrittenQuery())
                .errorMessage(c.getErrorMessage())
                .build();
    }

    private EvalRunCaseDetailVO toCaseDetail(EvalRunCase c) {
        return EvalRunCaseDetailVO.builder()
                .id(c.getId())
                .caseId(c.getCaseId())
                .query(c.getQuery())
                .tags(c.getTags())
                .relevantDocumentIds(c.getRelevantDocumentIds())
                .hit(c.getFirstHitRank() != null)
                .firstHitRank(c.getFirstHitRank())
                .hitAtK(c.getHitAtK())
                .recallAt5(c.getRecallAt5())
                .ndcgAt10(c.getNdcgAt10())
                .mrr(c.getMrr())
                .totalMs(c.getTotalMs())
                .rewritePath(c.getRewritePath())
                .rewrittenQuery(c.getRewrittenQuery())
                .errorMessage(c.getErrorMessage())
                .retrievedDocumentIds(c.getRetrievedDocumentIds())
                .retrievedScores(c.getRetrievedScores())
                .resultSnapshot(c.getResultSnapshot())
                .build();
    }

    private Map<Long, String> datasetNames(List<EvalRun> runs) {
        Set<Long> ids = runs.stream().map(EvalRun::getDatasetId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return datasetMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(EvalDataset::getId, EvalDataset::getName, (a, b) -> a));
    }

    private Map<Long, String> kbNames(List<EvalRun> runs) {
        Set<Long> ids = runs.stream().map(EvalRun::getKbId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return kbMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(KnowledgeBase::getId, KnowledgeBase::getName, (a, b) -> a));
    }

    private String datasetName(Long id) {
        EvalDataset dataset = id == null ? null : datasetMapper.selectById(id);
        return dataset == null ? null : dataset.getName();
    }

    private String kbName(Long id) {
        KnowledgeBase kb = id == null ? null : kbMapper.selectById(id);
        return kb == null ? null : kb.getName();
    }

    private boolean isGlobalAdmin(Long userId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        return user != null && "ADMIN".equals(user.getRole());
    }

    private List<String> parseStates(String state) {
        if (state == null || state.isBlank()) {
            return List.of();
        }
        List<String> states = Arrays.stream(state.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .distinct()
                .toList();
        if (states.stream().anyMatch(s -> !RUN_STATES.contains(s))) {
            throw new BusinessException(400, "state 包含非法状态");
        }
        return states;
    }

    private int normalizePage(Integer page) {
        int value = page == null ? 1 : page;
        if (value < 1) {
            throw new BusinessException(400, "page 必须大于等于 1");
        }
        return value;
    }

    private int normalizeSize(Integer size, int defaultValue) {
        int value = size == null ? defaultValue : size;
        if (value < 1 || value > 200) {
            throw new BusinessException(400, "size 必须在 1~200 之间");
        }
        return value;
    }

    private void checkRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new BusinessException(400, name + " 必须在 " + min + "~" + max + " 之间");
        }
    }

    private Map<String, Object> pageResult(long total, List<?> list) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("list", list);
        return result;
    }

    private Object hitAt(EvalRunCaseVO c, String k) {
        return c.getHitAtK() == null ? null : c.getHitAtK().get(k);
    }

    private String join(List<?> values) {
        if (values == null) {
            return "";
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining("|"));
    }

    private void appendCsvRow(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escapeCsv(values[i]));
        }
        csv.append("\r\n");
    }

    private String escapeCsv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }
}
