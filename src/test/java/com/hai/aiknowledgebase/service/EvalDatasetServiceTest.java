package com.hai.aiknowledgebase.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hai.aiknowledgebase.dto.eval.EvalCaseRequest;
import com.hai.aiknowledgebase.entity.EvalCase;
import com.hai.aiknowledgebase.entity.EvalDataset;
import com.hai.aiknowledgebase.entity.KnowledgeDocument;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.EvalCaseMapper;
import com.hai.aiknowledgebase.mapper.EvalDatasetMapper;
import com.hai.aiknowledgebase.mapper.EvalRunMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeBaseMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeDocumentMapper;
import com.hai.aiknowledgebase.mapper.UserMapper;
import com.hai.aiknowledgebase.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvalDatasetServiceTest {

    @Mock
    private EvalDatasetMapper datasetMapper;
    @Mock
    private EvalCaseMapper caseMapper;
    @Mock
    private EvalRunMapper runMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeBaseMapper kbMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private KbPermissionService kbPermissionService;

    @InjectMocks
    private EvalDatasetService datasetService;

    private EvalDataset dataset;
    private KnowledgeDocument doc1;
    private KnowledgeDocument doc2;
    private KnowledgeDocument doc3;

    @BeforeEach
    void setUp() {
        dataset = EvalDataset.builder()
                .id(10L)
                .kbId(1L)
                .name("测试集")
                .caseCount(0)
                .createdBy(7L)
                .isDeleted(0)
                .build();
        doc1 = document(1L, "a.md");
        doc2 = document(2L, "same.md");
        doc3 = document(3L, "same.md");
        when(datasetMapper.selectById(10L)).thenReturn(dataset);
    }

    @Test
    void importResolvesSingleAndDuplicateSourcesAndFailsMissingOrEmptyCases() {
        String json = """
                [
                  {"query":"单命中","relevant_sources":["a.md"]},
                  {"query":"同名多命中","relevant_sources":["same.md"]},
                  {"query":"未命中","relevant_sources":["missing.md"]},
                  {"query":"空关联","relevant_sources":[]}
                ]
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "cases.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
        when(documentMapper.selectList(any())).thenReturn(
                List.of(doc1), List.of(doc2, doc3), List.of());
        when(documentMapper.selectBatchIds(anyCollection())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return List.of(doc1, doc2, doc3).stream().filter(doc -> ids.contains(doc.getId())).toList();
        });
        when(caseMapper.selectCount(any())).thenReturn(2L);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(7L);
            Map<String, Object> result = datasetService.importCases(10L, file);

            assertThat(result.get("imported")).as("import result: %s", result).isEqualTo(2);
            assertThat(result.get("failed")).isEqualTo(2);
            assertThat((String) result.get("message"))
                    .contains("same.md 命中 2 个文档，已全部关联")
                    .contains("未找到文档: missing.md");
        }

        ArgumentCaptor<EvalCase> caseCaptor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseMapper, times(2)).insert(caseCaptor.capture());
        assertThat(caseCaptor.getAllValues().get(0).getRelevantDocumentIds()).containsExactly(1L);
        assertThat(caseCaptor.getAllValues().get(1).getRelevantDocumentIds()).containsExactly(2L, 3L);
        ArgumentCaptor<EvalDataset> datasetCaptor = ArgumentCaptor.forClass(EvalDataset.class);
        verify(datasetMapper).updateById(datasetCaptor.capture());
        assertThat(datasetCaptor.getValue().getCaseCount()).isEqualTo(2);
    }

    @Test
    void addAndDeleteCaseRefreshCaseCount() {
        EvalCaseRequest request = new EvalCaseRequest();
        request.setQuery("问题");
        request.setRelevantDocumentIds(List.of(1L));
        when(documentMapper.selectBatchIds(anyCollection())).thenReturn(List.of(doc1));
        when(caseMapper.selectCount(any())).thenReturn(1L, 0L);
        when(caseMapper.selectById(100L)).thenReturn(EvalCase.builder()
                .id(100L)
                .datasetId(10L)
                .query("问题")
                .relevantDocumentIds(List.of(1L))
                .isDeleted(0)
                .build());

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(7L);
            datasetService.addCase(10L, request);
            datasetService.deleteCase(10L, 100L);
        }

        ArgumentCaptor<EvalDataset> datasetCaptor = ArgumentCaptor.forClass(EvalDataset.class);
        verify(datasetMapper, times(2)).updateById(datasetCaptor.capture());
        assertThat(datasetCaptor.getAllValues().get(0).getCaseCount()).isEqualTo(1);
        assertThat(datasetCaptor.getAllValues().get(1).getCaseCount()).isZero();
        ArgumentCaptor<EvalCase> caseCaptor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseMapper).updateById(caseCaptor.capture());
        assertThat(caseCaptor.getValue().getIsDeleted()).isEqualTo(1);
    }

    @Test
    void datasetWithRunsCannotBeDeleted() {
        when(runMapper.selectCount(any())).thenReturn(1L);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(7L);
            assertThatThrownBy(() -> datasetService.delete(10L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("该评测集存在运行记录，无法删除");
        }

        verify(datasetMapper, never()).updateById(any(EvalDataset.class));
    }

    @Test
    void exportUsesRealFileNameInsteadOfDisplayTitle() throws Exception {
        doc1.setTitle("展示标题");
        EvalCase evalCase = EvalCase.builder()
                .id(100L)
                .datasetId(10L)
                .query("问题")
                .relevantDocumentIds(List.of(1L))
                .tags(List.of("标签"))
                .sortOrder(3)
                .isDeleted(0)
                .build();
        when(caseMapper.selectList(any())).thenReturn(List.of(evalCase));
        when(documentMapper.selectBatchIds(anyCollection())).thenReturn(List.of(doc1));

        String exported;
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(7L);
            exported = datasetService.exportDataset(10L);
        }

        JsonNode root = new ObjectMapper().readTree(exported);
        assertThat(root).hasSize(1);
        assertThat(root.get(0).get("relevant_sources").get(0).asText()).isEqualTo("a.md");
        assertThat(root.get(0).get("relevant_sources").get(0).asText()).isNotEqualTo("展示标题");
        assertThat(root.get(0).get("relevant_document_ids").get(0).asLong()).isEqualTo(1L);
        assertThat(root.get(0).get("sort_order").asInt()).isEqualTo(3);
    }

    private KnowledgeDocument document(Long id, String fileName) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setKbId(1L);
        document.setFileName(fileName);
        document.setIsDeleted(0);
        return document;
    }
}
