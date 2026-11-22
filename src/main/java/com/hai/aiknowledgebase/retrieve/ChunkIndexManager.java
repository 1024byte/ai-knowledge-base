package com.hai.aiknowledgebase.retrieve;

import com.hai.aiknowledgebase.common.KeywordIndex;
import com.hai.aiknowledgebase.common.VectorStatus;
import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import dev.langchain4j.data.segment.TextSegment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>片段索引生命周期管理器</h2>
 *
 * <p>统一维护 BM25 关键词倒排索引 {@link KeywordIndex} 与检索过滤所需的两个内存集合：
 * <ul>
 *   <li>{@code disabledChunkIds}：被禁用的片段主键（向量检索时过滤）</li>
 *   <li>{@code nonCompletedChunkIds}：未完成向量化的片段主键（向量检索时过滤）</li>
 * </ul>
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>启动时从 PGVector 重建 BM25 索引（{@code @PostConstruct}）</li>
 *   <li>加载/维护禁用片段与未完成向量化片段集合</li>
 *   <li>对外暴露 {@link #getKeywordIndex()} 供文档入库、删除等场景同步索引</li>
 * </ul>
 *
 * <p>检索原语（向量/关键词）由 {@code VectorRetriever} / {@code KeywordRetriever} 负责，
 * 本类不包含任何检索逻辑。</p>
 */
@Slf4j
@Component
public class ChunkIndexManager {

    /** 基于 BM25 算法的内存关键词倒排索引 */
    private final KeywordIndex keywordIndex;

    /** 已禁用片段 ID 集合（chunk 数据库主键），用于向量检索时过滤禁用片段 */
    private final Set<Long> disabledChunkIds = ConcurrentHashMap.newKeySet();

    /** 未完成向量化的片段 ID 集合（vector_status != 2），用于向量检索时过滤非完成状态片段 */
    private final Set<Long> nonCompletedChunkIds = ConcurrentHashMap.newKeySet();

    /** JDBC Template，用于从 PGVector embeddings 表中读取数据重建索引 */
    private final JdbcTemplate jdbcTemplate;

    /** PGVector 向量表名，默认 "embeddings"，与 {@code vectorstore.pgvector.table-name} 配置一致 */
    @Value("${vectorstore.pgvector.table-name:embeddings}")
    private String tableName;

    /**
     * 构造片段索引管理器。
     *
     * @param tokenizerService 中文分词服务，用于 BM25 关键词索引的分词
     * @param jdbcTemplate     JDBC Template，用于启动时从 PGVector 重建 BM25 索引
     */
    public ChunkIndexManager(ChineseTokenizerService tokenizerService, JdbcTemplate jdbcTemplate) {
        this.keywordIndex = new KeywordIndex(tokenizerService);
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 启动时 BM25 索引重建 ====================

    /**
     * <h3>应用启动时从 PGVector 重建 BM25 关键词索引</h3>
     *
     * <p>因为 KeywordIndex 是纯内存结构（{@link ConcurrentHashMap}），
     * 应用重启后索引数据会全部丢失。此方法在 Spring 容器初始化完成后自动执行，
     * 从 PGVector 的 embeddings 表中读取所有 chunk 数据，重建 BM25 倒排索引。</p>
     *
     * <h4>重建流程</h4>
     * <ol>
     *   <li>从 PGVector embeddings 表查询所有 chunk（text + metadata）</li>
     *   <li>按 {@code metadata->>'document_id'} 分组，组内按 {@code embedding_id} 排序</li>
     *   <li>为每组内的 chunk 分配顺序索引（0, 1, 2...），
     *       重构 chunkId 格式为 {@code "sourceFileName_chunkIndex"}，
     *       与 {@link VectorizationService} 入库时保持完全一致</li>
     *   <li>调用 {@link KeywordIndex#index} 逐条重建索引</li>
     * </ol>
     *
     * <p>重建失败时记录错误日志但不抛异常，不阻塞应用启动。</p>
     */
    @PostConstruct
    public void rebuildKeywordIndex() {
        try {
            log.info("开始从 PGVector 重建 BM25 关键词索引...");
            long startTime = System.currentTimeMillis();

            // 先从 chunk 表加载禁用片段 ID（用于重建时跳过 + 向量检索时过滤）
            reloadDisabledChunkIds();
            // 加载未完成向量化的片段 ID（重建时跳过 + 向量检索时过滤）
            reloadNonCompletedChunkIds();

            // 查询所有 chunk：按 document_id 分组，组内按 embedding_id 排序
            // 同时查询 source（文件名）、chunk_id（片段主键）用于重建 chunkId
            String sql = String.format(
                    "SELECT embedding_id, text, metadata->>'source' AS source, metadata->>'document_id' AS doc_id, " +
                    "metadata->>'kb_id' AS kb_id, metadata->>'chunk_id' AS chunk_id " +
                    "FROM %s " +
                    "ORDER BY metadata->>'kb_id', metadata->>'document_id', embedding_id",
                    tableName
            );
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            if (rows.isEmpty()) {
                log.info("PGVector 中无数据，BM25 索引为空，跳过重建");
                return;
            }

            // 按 document_id 分组，每组内按 embedding_id 顺序分配 chunkIndex（兼容旧数据无 chunk_id 时回退）
            String currentDocId = null;
            int chunkIndex = 0;
            int indexedCount = 0;
            int skippedCount = 0;

            for (Map<String, Object> row : rows) {
                String docId = String.valueOf(row.get("doc_id"));
                String source = String.valueOf(row.get("source"));
                String kbId = (String) row.get("kb_id");
                String text = String.valueOf(row.get("text"));
                Object chunkIdObj = row.get("chunk_id");

                // 跳过空文本
                if (text == null || text.isBlank()) {
                    continue;
                }

                // 检测到新的 document_id → 重置 chunkIndex
                if (!docId.equals(currentDocId)) {
                    currentDocId = docId;
                    chunkIndex = 0;
                }

                // 已禁用片段不进入 BM25 索引（向量仍保留，检索时由向量路径过滤）
                if (chunkIdObj != null && disabledChunkIds.contains(parseChunkId(chunkIdObj))) {
                    skippedCount++;
                    continue;
                }

                // 未完成向量化的片段不进入 BM25 索引（避免召回处理中/失败的旧内容）
                if (chunkIdObj != null && nonCompletedChunkIds.contains(parseChunkId(chunkIdObj))) {
                    skippedCount++;
                    continue;
                }

                // 重构 chunkId：优先使用 chunk_id（数据库主键，编辑/合并/拆分后保持稳定），
                // 旧数据无 chunk_id 时回退为 chunkIndex。
                // 格式："kbId_sourceFileName_chunkId"，如 "1_时态.md_42"
                String suffix = chunkIdObj != null ? String.valueOf(chunkIdObj) : String.valueOf(chunkIndex);
                String chunkId = (kbId != null && !kbId.isBlank())
                        ? kbId + "_" + source + "_" + suffix
                        : source + "_" + suffix;
                keywordIndex.index(chunkId, text);
                chunkIndex++;
                indexedCount++;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("BM25 关键词索引重建完成: 共 {} 条 chunk (跳过禁用 {} 条), 耗时 {} ms",
                    indexedCount, skippedCount, elapsed);

        } catch (Exception e) {
            log.error("BM25 关键词索引重建失败，应用将继续运行但降级为纯向量检索", e);
            // 不抛异常，让应用继续启动。后续可通过重新上传文档逐步恢复
        }
    }

    /**
     * <h3>从 chunk 表重新加载禁用片段 ID 集合</h3>
     *
     * <p>将数据库中 {@code enabled=false} 的片段主键加载到内存集合，
     * 供向量检索路径过滤禁用片段使用。</p>
     */
    private void reloadDisabledChunkIds() {
        try {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM knowledge_document_chunk WHERE enabled = false", Long.class);
            disabledChunkIds.clear();
            disabledChunkIds.addAll(ids);
            log.info("加载禁用片段集合完成: {} 个禁用片段", disabledChunkIds.size());
        } catch (Exception e) {
            log.warn("加载禁用片段集合失败，禁用片段可能无法在向量检索中被过滤", e);
        }
    }

    /**
     * <h3>从 chunk 表重新加载未完成向量化的片段 ID 集合</h3>
     *
     * <p>将 {@code vector_status != 2}（待处理/处理中/失败）的片段主键
     * 加载到内存集合，供向量检索路径过滤非完成状态片段使用。</p>
     */
    private void reloadNonCompletedChunkIds() {
        try {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM knowledge_document_chunk WHERE vector_status IS DISTINCT FROM 2", Long.class);
            nonCompletedChunkIds.clear();
            nonCompletedChunkIds.addAll(ids);
            log.info("加载未完成向量化片段集合完成: {} 个片段", nonCompletedChunkIds.size());
        } catch (Exception e) {
            log.warn("加载未完成向量化片段集合失败，非完成状态片段可能被错误召回", e);
        }
    }

    /**
     * 标记片段启用/禁用状态（维护内存集合，供向量检索路径过滤）。
     *
     * @param chunkId 片段数据库主键
     * @param enabled true=启用（移出禁用集合），false=禁用（加入禁用集合）
     */
    public void markChunkEnabled(Long chunkId, boolean enabled) {
        if (enabled) {
            disabledChunkIds.remove(chunkId);
        } else {
            disabledChunkIds.add(chunkId);
        }
    }

    /**
     * 标记片段向量化状态（维护内存集合，供向量检索路径过滤）。
     *
     * @param chunkId      片段数据库主键
     * @param vectorStatus 片段向量化状态（{@link VectorStatus#CHUNK_COMPLETED} 为完成）
     */
    public void markChunkVectorized(Long chunkId, int vectorStatus) {
        if (vectorStatus == VectorStatus.CHUNK_COMPLETED) {
            nonCompletedChunkIds.remove(chunkId);
        } else {
            nonCompletedChunkIds.add(chunkId);
        }
    }

    /**
     * 判断片段是否被禁用（向量检索过滤用）。
     */
    public boolean isChunkDisabled(TextSegment segment) {
        String chunkId = segment.metadata().getString("chunk_id");
        if (chunkId == null || chunkId.isBlank()) {
            return false;
        }
        try {
            return disabledChunkIds.contains(Long.parseLong(chunkId));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 判断片段是否未完成向量化（向量检索过滤用）。
     */
    public boolean isChunkNotCompleted(TextSegment segment) {
        String chunkId = segment.metadata().getString("chunk_id");
        if (chunkId == null || chunkId.isBlank()) {
            return false;
        }
        try {
            return nonCompletedChunkIds.contains(Long.parseLong(chunkId));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 解析 chunk_id 字符串为 Long，解析失败返回 null。
     */
    private Long parseChunkId(Object chunkIdObj) {
        try {
            return Long.parseLong(String.valueOf(chunkIdObj));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取关键词索引实例。
     * 外部服务（如文档入库/删除）在需要同步索引时，调用此方法获取索引实例。
     *
     * @return BM25 关键词倒排索引实例
     */
    public KeywordIndex getKeywordIndex() {
        return keywordIndex;
    }
}
