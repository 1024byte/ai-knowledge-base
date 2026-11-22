package com.hai.aiknowledgebase.retrieve;

import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <h2>关键词检索原语（BM25）</h2>
 *
 * <p>封装 BM25 内存倒排索引的关键词精确匹配检索，返回带 BM25 分数的 {@link RankedResult}。
 * 只负责检索原语本身，不包含 RRF 融合、Rerank 精排、降权等编排逻辑。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordRetriever {

    /** 片段索引管理器，提供 BM25 关键词倒排索引 */
    private final ChunkIndexManager chunkIndexManager;

    /** JDBC Template，用于按文档范围过滤时反查片段主键 → 文档 ID 映射 */
    private final JdbcTemplate jdbcTemplate;

    public List<RankedResult> keywordSearchRanked(String query, int topK, Set<Long> kbIds) {
        return keywordSearchRanked(query, topK, kbIds, null);
    }

    /**
     * <h3>关键词检索（带分数，支持知识库与文档范围过滤）</h3>
     *
     * <p>在 kbIds 知识库隔离基础上，增加 documentIds 文档范围过滤。
     * 返回的 TextSegment 元数据会额外携带 {@code chunk_id}（片段主键），
     * 供下游 RRF 融合以片段主键为去重键时使用。</p>
     *
     * @param query       用户查询文本
     * @param topK        返回结果数量上限
     * @param kbIds       知识库范围（null 表示不过滤）
     * @param documentIds 文档范围（null/空 表示不过滤）
     * @return 带 BM25 分数的排序结果列表
     */
    public List<RankedResult> keywordSearchRanked(String query, int topK, Set<Long> kbIds, Set<Long> documentIds) {
        try {
            // 步骤1：在 BM25 倒排索引中检索，返回 (docId, BM25分数) 的有序列表
            // kbIds == null 表示不过滤；空集合表示无可见知识库；否则按 kbId 前缀集合过滤
            Map<Long, Long> chunkIdToDocumentId = loadChunkIdToDocumentId(documentIds);
            List<Map.Entry<String, Double>> entries =
                    chunkIndexManager.getKeywordIndex().search(query, topK, kbIds, documentIds, chunkIdToDocumentId);

            List<RankedResult> results = new ArrayList<>();
            for (var entry : entries) {
                String docId = entry.getKey();

                // 步骤2：根据 docId 获取文档原文
                String text = chunkIndexManager.getKeywordIndex().getDocText(docId);
                if (text == null) continue;

                // 步骤3：从 docId 中提取 source 标识
                // docId 格式: "sourceFileName_chunkIndex"，如 "时态.md_5"
                String source = extractSourceFromDocId(docId);

                // 步骤4：包装为 TextSegment，与向量检索结果格式统一，并附带 chunk_id 主键
                dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata()
                        .put("source", source)
                        .put("doc_id", docId);
                Long chunkId = parseChunkIdFromDocId(docId);
                if (chunkId != null) {
                    metadata.put("chunk_id", String.valueOf(chunkId));
                }
                TextSegment segment = TextSegment.from(text, metadata);

                results.add(new RankedResult(segment, entry.getValue(), null, entry.getValue()));
            }
            return results;
        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 从 BM25 docId（格式 {@code "kbId_source_chunkId"}）中解析片段主键（最后一个下划线之后）。
     * 解析失败返回 null。
     */
    private Long parseChunkIdFromDocId(String docId) {
        if (docId == null || docId.isEmpty()) {
            return null;
        }
        int idx = docId.lastIndexOf('_');
        if (idx < 0 || idx == docId.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(docId.substring(idx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 构建「片段主键 → 文档 ID」映射，供关键词检索按文档范围过滤。
     *
     * @param documentIds 文档 ID 集合（null/空返回空映射）
     */
    private Map<Long, Long> loadChunkIdToDocumentId(Set<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        String inClause = documentIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, document_id FROM knowledge_document_chunk WHERE document_id IN (" + inClause + ")");
        Map<Long, Long> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            Object documentId = row.get("document_id");
            if (id == null || documentId == null) {
                continue;
            }
            map.put(((Number) id).longValue(), ((Number) documentId).longValue());
        }
        return map;
    }

    /**
     * <h3>从 docId 中提取文档来源标识</h3>
     *
     * <p>docId 的命名规则有两种：</p>
     * <ul>
     *   <li>知识库隔离格式：{@code "kbId_documentName_chunkIndex"}，如 {@code "1_时态.md_0"}</li>
     *   <li>旧版格式：{@code "documentName_chunkIndex"}，如 {@code "章程.pdf_0"}</li>
     * </ul>
     *
     * <p>提取逻辑：先去掉最后一个下划线之后的 chunkIndex；若剩余部分第一个下划线之前
     * 为纯数字（kbId 前缀），则继续去掉该前缀，最终返回文档来源标识。</p>
     *
     * @param docId 文档唯一标识
     * @return 提取出的文档来源标识
     */
    private String extractSourceFromDocId(String docId) {
        if (docId == null || docId.isEmpty()) {
            return docId;
        }
        int lastUnderscore = docId.lastIndexOf("_");
        if (lastUnderscore <= 0) {
            return docId;
        }
        // 去掉 chunkIndex 后缀
        String withoutChunkIndex = docId.substring(0, lastUnderscore);
        // 若第一个下划线之前为纯数字（kbId 前缀），则继续去掉 kbId 前缀
        int firstUnderscore = withoutChunkIndex.indexOf("_");
        if (firstUnderscore > 0 && isNumeric(withoutChunkIndex.substring(0, firstUnderscore))) {
            return withoutChunkIndex.substring(firstUnderscore + 1);
        }
        return withoutChunkIndex;
    }

    /**
     * 判断字符串是否为纯数字（用于识别 chunkId 中的 kbId 前缀）
     */
    private boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
