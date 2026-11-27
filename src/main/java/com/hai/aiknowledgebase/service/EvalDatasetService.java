package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hai.aiknowledgebase.common.Role;
import com.hai.aiknowledgebase.dto.eval.EvalCaseRequest;
import com.hai.aiknowledgebase.dto.eval.EvalCaseVO;
import com.hai.aiknowledgebase.dto.eval.EvalDatasetVO;
import com.hai.aiknowledgebase.entity.EvalCase;
import com.hai.aiknowledgebase.entity.EvalDataset;
import com.hai.aiknowledgebase.entity.EvalRun;
import com.hai.aiknowledgebase.entity.KnowledgeBase;
import com.hai.aiknowledgebase.entity.KnowledgeDocument;
import com.hai.aiknowledgebase.entity.User;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.EvalCaseMapper;
import com.hai.aiknowledgebase.mapper.EvalDatasetMapper;
import com.hai.aiknowledgebase.mapper.EvalRunMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeBaseMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeDocumentMapper;
import com.hai.aiknowledgebase.mapper.UserMapper;
import com.hai.aiknowledgebase.mapper.handler.JsonbTypeHandler;
import com.hai.aiknowledgebase.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评测集与 Case 管理。
 *
 * <p>权限：对所属知识库具备 VIEWER 即可创建与维护；删除评测集需创建人或 KB 管理员。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetService {

    private static final int MAX_IMPORT_CASES = 500;
    private static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024;
    private static final int MAX_MESSAGE_ITEMS = 20;

    private final EvalDatasetMapper datasetMapper;
    private final EvalCaseMapper caseMapper;
    private final EvalRunMapper runMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final UserMapper userMapper;
    private final KbPermissionService kbPermissionService;

    private final ObjectMapper objectMapper = JsonbTypeHandler.mapper();

    // ==================== 评测集 ====================

    public List<EvalDatasetVO> list(Long kbId, String keyword) {
        Long userId = SecurityUtils.getCurrentUserId();
        Set<Long> visibleKbIds = new LinkedHashSet<>(kbPermissionService.getVisibleKbIds(userId));

        LambdaQueryWrapper<EvalDataset> wrapper = new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getIsDeleted, 0)
                .orderByDesc(EvalDataset::getId);
        if (kbId != null) {
            checkKbVisible(visibleKbIds, kbId);
            wrapper.eq(EvalDataset::getKbId, kbId);
        } else if (!visibleKbIds.isEmpty()) {
            wrapper.in(EvalDataset::getKbId, visibleKbIds);
        } else {
            return List.of();
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(EvalDataset::getName, keyword.trim());
        }

        List<EvalDataset> datasets = datasetMapper.selectList(wrapper);
        Map<Long, String> kbNames = kbNames(datasets.stream().map(EvalDataset::getKbId).collect(Collectors.toSet()));
        return datasets.stream().map(d -> {
            EvalRun lastRun = latestRun(d.getId());
            return toVO(d, kbNames.get(d.getKbId()), lastRun);
        }).collect(Collectors.toList());
    }

    public EvalDatasetVO.Detail detail(Long id) {
        EvalDataset dataset = requireDataset(id);
        EvalRun lastRun = latestRun(id);
        EvalDatasetVO.Detail detail = new EvalDatasetVO.Detail();
        EvalDatasetVO base = toVO(dataset, kbName(dataset.getKbId()), lastRun);
        detail.setId(base.getId());
        detail.setKbId(base.getKbId());
        detail.setKbName(base.getKbName());
        detail.setName(base.getName());
        detail.setDescription(base.getDescription());
        detail.setCaseCount(base.getCaseCount());
        detail.setLastRunAt(base.getLastRunAt());
        detail.setLastRunState(base.getLastRunState());
        detail.setCreatedAt(base.getCreatedAt());
        detail.setUpdatedAt(base.getUpdatedAt());
        detail.setCases(listCaseVOs(id));
        return detail;
    }

    @Transactional
    public Long create(Long kbId, String name, String description) {
        if (kbId == null) {
            throw new BusinessException(400, "参数校验失败：kbId 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "参数校验失败：name 不能为空");
        }
        kbPermissionService.checkPermission(SecurityUtils.getCurrentUserId(), kbId, Role.VIEWER);
        EvalDataset dataset = EvalDataset.builder()
                .kbId(kbId)
                .name(name.trim())
                .description(description)
                .caseCount(0)
                .createdBy(SecurityUtils.getCurrentUserId())
                .createdByName(SecurityUtils.getCurrentUsername())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .isDeleted(0)
                .build();
        datasetMapper.insert(dataset);
        return dataset.getId();
    }

    @Transactional
    public void update(Long id, String name, String description) {
        EvalDataset dataset = requireDataset(id);
        if (name != null) {
            if (name.isBlank()) {
                throw new BusinessException(400, "参数校验失败：name 不能为空");
            }
            dataset.setName(name.trim());
        }
        if (description != null) {
            dataset.setDescription(description);
        }
        dataset.setUpdatedAt(LocalDateTime.now());
        datasetMapper.updateById(dataset);
    }

    @Transactional
    public void delete(Long id) {
        EvalDataset dataset = requireDataset(id);
        checkManagePermission(dataset);
        Long runCount = runMapper.selectCount(new LambdaQueryWrapper<EvalRun>().eq(EvalRun::getDatasetId, id));
        if (runCount != null && runCount > 0) {
            throw new BusinessException(400, "该评测集存在运行记录，无法删除");
        }
        dataset.setIsDeleted(1);
        dataset.setUpdatedAt(LocalDateTime.now());
        datasetMapper.updateById(dataset);
    }

    // ==================== Case ====================

    public List<EvalCaseVO> listCaseVOs(Long datasetId) {
        List<EvalCase> cases = caseMapper.selectList(new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getDatasetId, datasetId)
                .eq(EvalCase::getIsDeleted, 0)
                .orderByAsc(EvalCase::getSortOrder)
                .orderByAsc(EvalCase::getId));
        Set<Long> docIds = cases.stream()
                .filter(c -> c.getRelevantDocumentIds() != null)
                .flatMap(c -> c.getRelevantDocumentIds().stream())
                .collect(Collectors.toSet());
        Map<Long, KnowledgeDocument> docs = documents(docIds);
        return cases.stream().map(c -> toCaseVO(c, docs)).collect(Collectors.toList());
    }

    @Transactional
    public Long addCase(Long datasetId, EvalCaseRequest request) {
        EvalDataset dataset = requireDataset(datasetId);
        validateCase(dataset, request.getQuery(), request.getRelevantDocumentIds());
        EvalCase evalCase = EvalCase.builder()
                .datasetId(datasetId)
                .query(request.getQuery().trim())
                .relevantDocumentIds(request.getRelevantDocumentIds())
                .tags(request.getTags() == null ? List.of() : request.getTags())
                .sortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder())
                .createdAt(LocalDateTime.now())
                .isDeleted(0)
                .build();
        caseMapper.insert(evalCase);
        refreshCaseCount(datasetId);
        return evalCase.getId();
    }

    @Transactional
    public Map<String, Object> addCasesBatch(Long datasetId, List<EvalCaseRequest> requests) {
        EvalDataset dataset = requireDataset(datasetId);
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException(400, "参数校验失败：cases 不能为空");
        }
        int imported = 0;
        int failed = 0;
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            EvalCaseRequest request = requests.get(i);
            try {
                validateCase(dataset, request.getQuery(), request.getRelevantDocumentIds());
                caseMapper.insert(EvalCase.builder()
                        .datasetId(datasetId)
                        .query(request.getQuery().trim())
                        .relevantDocumentIds(request.getRelevantDocumentIds())
                        .tags(request.getTags() == null ? List.of() : request.getTags())
                        .sortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder())
                        .createdAt(LocalDateTime.now())
                        .isDeleted(0)
                        .build());
                imported++;
            } catch (BusinessException e) {
                failed++;
                if (problems.size() < MAX_MESSAGE_ITEMS) {
                    problems.add("第 " + (i + 1) + " 条: " + e.getMessage());
                }
            }
        }
        refreshCaseCount(datasetId);
        Map<String, Object> result = new HashMap<>();
        result.put("imported", imported);
        result.put("failed", failed);
        result.put("message", String.join("; ", problems));
        return result;
    }

    @Transactional
    public void updateCase(Long datasetId, Long caseId, EvalCaseRequest request) {
        EvalDataset dataset = requireDataset(datasetId);
        EvalCase evalCase = requireCase(datasetId, caseId);
        if (request.getQuery() != null) {
            if (request.getQuery().isBlank()) {
                throw new BusinessException(400, "参数校验失败：query 不能为空");
            }
            evalCase.setQuery(request.getQuery().trim());
        }
        if (request.getRelevantDocumentIds() != null) {
            validateCase(dataset, evalCase.getQuery(), request.getRelevantDocumentIds());
            evalCase.setRelevantDocumentIds(request.getRelevantDocumentIds());
        }
        if (request.getTags() != null) {
            evalCase.setTags(request.getTags());
        }
        if (request.getSortOrder() != null) {
            evalCase.setSortOrder(request.getSortOrder());
        }
        caseMapper.updateById(evalCase);
    }

    @Transactional
    public void deleteCase(Long datasetId, Long caseId) {
        requireDataset(datasetId);
        EvalCase evalCase = requireCase(datasetId, caseId);
        evalCase.setIsDeleted(1);
        caseMapper.updateById(evalCase);
        refreshCaseCount(datasetId);
    }

    // ==================== 导入导出 ====================

    /**
     * 导入 JSON 文件。
     *
     * <p>支持两种标注方式：{@code relevant_document_ids}（文档主键，导出格式）与
     * {@code relevant_sources}（文件名，按 knowledge_document.file_name 在该 KB 内精确匹配，
     * 同名多条全部关联并记 warning）。两种方式最终关联为空都计 failed。</p>
     */
    @Transactional
    public Map<String, Object> importCases(Long datasetId, MultipartFile file) {
        EvalDataset dataset = requireDataset(datasetId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "参数校验失败：file 不能为空");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new BusinessException(400, "参数校验失败：文件不能超过 2MB");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(file.getInputStream());
        } catch (IOException e) {
            throw new BusinessException(400, "参数校验失败：JSON 解析失败 - " + e.getMessage());
        }
        if (root == null || !root.isArray()) {
            throw new BusinessException(400, "参数校验失败：导入文件必须是 JSON 数组");
        }
        if (root.size() > MAX_IMPORT_CASES) {
            throw new BusinessException(400, "参数校验失败：单批最多 " + MAX_IMPORT_CASES + " 条");
        }

        int imported = 0;
        int failed = 0;
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            String query = node.path("query").asText(null);
            try {
                List<Long> docIds = resolveDocumentIds(dataset, node, problems);
                validateCase(dataset, query, docIds);
                caseMapper.insert(EvalCase.builder()
                        .datasetId(datasetId)
                        .query(query.trim())
                        .relevantDocumentIds(docIds)
                        .tags(readStringList(node.path("tags")))
                        .sortOrder(node.path("sort_order").asInt(0))
                        .createdAt(LocalDateTime.now())
                        .isDeleted(0)
                        .build());
                imported++;
            } catch (BusinessException e) {
                failed++;
                addProblem(problems, "第 " + (i + 1) + " 条: " + e.getMessage());
            }
        }
        refreshCaseCount(datasetId);

        Map<String, Object> result = new HashMap<>();
        result.put("imported", imported);
        result.put("failed", failed);
        result.put("message", String.join("; ", problems));
        log.info("评测集导入完成: datasetId={}, imported={}, failed={}", datasetId, imported, failed);
        return result;
    }

    /** 导出为与导入兼容的 JSON 数组字符串。 */
    public String exportDataset(Long datasetId) {
        requireDataset(datasetId);
        List<EvalCase> cases = caseMapper.selectList(new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getDatasetId, datasetId)
                .eq(EvalCase::getIsDeleted, 0)
                .orderByAsc(EvalCase::getSortOrder)
                .orderByAsc(EvalCase::getId));
        Set<Long> docIds = cases.stream()
                .filter(c -> c.getRelevantDocumentIds() != null)
                .flatMap(c -> c.getRelevantDocumentIds().stream())
                .collect(Collectors.toSet());
        Map<Long, KnowledgeDocument> docs = documents(docIds);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (EvalCase c : cases) {
            Map<String, Object> row = new HashMap<>();
            row.put("query", c.getQuery());
            row.put("relevant_document_ids", c.getRelevantDocumentIds());
            row.put("relevant_sources", c.getRelevantDocumentIds() == null ? List.of()
                    : c.getRelevantDocumentIds().stream()
                            .map(docs::get)
                            .filter(Objects::nonNull)
                            .map(KnowledgeDocument::getFileName)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList()));
            row.put("tags", c.getTags() == null ? List.of() : c.getTags());
            row.put("sort_order", c.getSortOrder());
            rows.add(row);
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows);
        } catch (IOException e) {
            throw new BusinessException(500, "导出失败: " + e.getMessage());
        }
    }

    // ==================== 内部工具 ====================

    private List<Long> resolveDocumentIds(EvalDataset dataset, JsonNode node, List<String> problems) {
        Set<Long> ids = new LinkedHashSet<>();
        JsonNode idNode = node.path("relevant_document_ids");
        if (idNode.isArray()) {
            idNode.forEach(n -> {
                if (n.canConvertToLong()) {
                    ids.add(n.asLong());
                }
            });
        }
        JsonNode sources = node.path("relevant_sources");
        if (sources.isArray()) {
            for (JsonNode sourceNode : sources) {
                String fileName = sourceNode.asText(null);
                if (fileName == null || fileName.isBlank()) {
                    continue;
                }
                List<KnowledgeDocument> matched = documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKbId, dataset.getKbId())
                        .eq(KnowledgeDocument::getFileName, fileName.trim())
                        .eq(KnowledgeDocument::getIsDeleted, 0));
                if (matched.isEmpty()) {
                    throw new BusinessException(400, "未找到文档: " + fileName);
                } else {
                    matched.forEach(d -> ids.add(d.getId()));
                    if (matched.size() > 1) {
                        addProblem(problems, fileName + " 命中 " + matched.size() + " 个文档，已全部关联");
                    }
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private List<String> readStringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(n -> values.add(n.asText()));
        return values;
    }

    private void addProblem(List<String> problems, String message) {
        if (problems.size() < MAX_MESSAGE_ITEMS) {
            problems.add(message);
        }
    }

    private void validateCase(EvalDataset dataset, String query, List<Long> relevantDocumentIds) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(400, "参数校验失败：query 不能为空");
        }
        if (relevantDocumentIds == null || relevantDocumentIds.isEmpty()) {
            throw new BusinessException(400, "参数校验失败：relevantDocumentIds 至少 1 个相关文档");
        }
        List<KnowledgeDocument> docs = documentMapper.selectBatchIds(relevantDocumentIds);
        long valid = docs.stream()
                .filter(Objects::nonNull)
                .filter(d -> Objects.equals(d.getKbId(), dataset.getKbId()))
                .filter(d -> d.getIsDeleted() == null || d.getIsDeleted() == 0)
                .count();
        if (valid != relevantDocumentIds.size()) {
            throw new BusinessException(400, "参数校验失败：相关文档必须属于该评测集知识库且未删除");
        }
    }

    private EvalDataset requireDataset(Long id) {
        EvalDataset dataset = id == null ? null : datasetMapper.selectById(id);
        if (dataset == null || (dataset.getIsDeleted() != null && dataset.getIsDeleted() == 1)) {
            throw new BusinessException(400, "评测集不存在");
        }
        kbPermissionService.checkPermission(SecurityUtils.getCurrentUserId(), dataset.getKbId(), Role.VIEWER);
        return dataset;
    }

    private EvalCase requireCase(Long datasetId, Long caseId) {
        EvalCase evalCase = caseMapper.selectById(caseId);
        if (evalCase == null || !Objects.equals(evalCase.getDatasetId(), datasetId)
                || (evalCase.getIsDeleted() != null && evalCase.getIsDeleted() == 1)) {
            throw new BusinessException(400, "Case 不存在");
        }
        return evalCase;
    }

    private void checkManagePermission(EvalDataset dataset) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (Objects.equals(dataset.getCreatedBy(), userId)) {
            return;
        }
        User user = userMapper.selectById(userId);
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw new BusinessException(403, "仅评测集创建人或管理员可删除");
        }
    }

    private void checkKbVisible(Set<Long> visibleKbIds, Long kbId) {
        if (!visibleKbIds.contains(kbId)) {
            throw new BusinessException(403, "无权限访问该知识库");
        }
    }

    private void refreshCaseCount(Long datasetId) {
        Long count = caseMapper.selectCount(new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getDatasetId, datasetId)
                .eq(EvalCase::getIsDeleted, 0));
        EvalDataset update = new EvalDataset();
        update.setId(datasetId);
        update.setCaseCount(count == null ? 0 : count.intValue());
        update.setUpdatedAt(LocalDateTime.now());
        datasetMapper.updateById(update);
    }

    private EvalRun latestRun(Long datasetId) {
        List<EvalRun> runs = runMapper.selectList(new LambdaQueryWrapper<EvalRun>()
                .eq(EvalRun::getDatasetId, datasetId)
                .orderByDesc(EvalRun::getId)
                .last("LIMIT 1"));
        return runs.isEmpty() ? null : runs.get(0);
    }

    private EvalDatasetVO toVO(EvalDataset dataset, String kbName, EvalRun lastRun) {
        return EvalDatasetVO.builder()
                .id(dataset.getId())
                .kbId(dataset.getKbId())
                .kbName(kbName)
                .name(dataset.getName())
                .description(dataset.getDescription())
                .caseCount(dataset.getCaseCount())
                .lastRunAt(lastRun == null ? null
                        : (lastRun.getFinishedAt() != null ? lastRun.getFinishedAt() : lastRun.getStartedAt()))
                .lastRunState(lastRun == null ? null : lastRun.getState())
                .createdAt(dataset.getCreatedAt())
                .updatedAt(dataset.getUpdatedAt())
                .build();
    }

    private EvalCaseVO toCaseVO(EvalCase evalCase, Map<Long, KnowledgeDocument> docs) {
        List<EvalCaseVO.DocBrief> briefs = new ArrayList<>();
        if (evalCase.getRelevantDocumentIds() != null) {
            for (Long docId : evalCase.getRelevantDocumentIds()) {
                KnowledgeDocument doc = docs.get(docId);
                String name = doc == null ? "(已删除)"
                        : (doc.getTitle() != null && !doc.getTitle().isBlank() ? doc.getTitle() : doc.getFileName());
                briefs.add(EvalCaseVO.DocBrief.builder().id(docId).name(name).build());
            }
        }
        return EvalCaseVO.builder()
                .id(evalCase.getId())
                .query(evalCase.getQuery())
                .relevantDocumentIds(evalCase.getRelevantDocumentIds())
                .relevantDocuments(briefs)
                .tags(evalCase.getTags())
                .sortOrder(evalCase.getSortOrder())
                .createdAt(evalCase.getCreatedAt())
                .build();
    }

    private Map<Long, KnowledgeDocument> documents(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return documentMapper.selectBatchIds(ids).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(KnowledgeDocument::getId, d -> d, (a, b) -> a));
    }

    private String kbName(Long kbId) {
        KnowledgeBase kb = kbId == null ? null : kbMapper.selectById(kbId);
        return kb == null ? null : kb.getName();
    }

    private Map<Long, String> kbNames(Set<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Map.of();
        }
        return kbMapper.selectBatchIds(kbIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(KnowledgeBase::getId, KnowledgeBase::getName, (a, b) -> a));
    }
}
