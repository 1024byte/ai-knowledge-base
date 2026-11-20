package com.hai.aiknowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hai.aiknowledgebase.entity.KnowledgeDocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <h2>文档切片表 Mapper</h2>
 *
 * <p>提供切片的基本 CRUD 操作，包括根据 documentId 全量删除（用于覆盖写入）
 * 与按向量化状态分组统计（用于文档级状态聚合）。</p>
 */
@Mapper
public interface KnowledgeDocumentChunkMapper extends BaseMapper<KnowledgeDocumentChunk> {

    /**
     * 按文档 ID 分组统计各向量化状态的片段数量。
     *
     * <p>返回键：{@code vector_status}（片段向量化状态）、{@code cnt}（数量）。
     * 用于 {@code recomputeDocumentVectorStatus} 向上聚合文档级状态。</p>
     *
     * <p>注意：PostgreSQL 会把未加引号的别名折叠为小写，MyBatis 返回的 Map 键区分大小写，
     * 故别名必须使用小写蛇形命名，与 {@code row.get(...)} 的键保持一致。</p>
     *
     * @param documentId 文档 ID
     * @return 各状态片段数量列表
     */
    @Select("SELECT vector_status AS vector_status, COUNT(*) AS cnt " +
            "FROM knowledge_document_chunk WHERE document_id = #{documentId} GROUP BY vector_status")
    List<Map<String, Object>> countByVectorStatus(@Param("documentId") Long documentId);

    /**
     * 按文档 ID 集合批量查询片段主键与所属文档 ID。
     *
     * <p>返回键：{@code id}（片段主键）、{@code document_id}（所属文档 ID）。
     * 用于召回测试中构建「片段主键 → 文档 ID」映射，从而在 BM25 关键词检索时按文档范围过滤。</p>
     *
     * @param documentIds 文档 ID 集合
     * @return 片段主键与文档 ID 的映射列表
     */
    @Select("<script>" +
            "SELECT id, document_id FROM knowledge_document_chunk WHERE document_id IN " +
            "<foreach collection='documentIds' item='did' open='(' separator=',' close=')'>#{did}</foreach>" +
            "</script>")
    List<Map<String, Object>> selectChunkIdDocumentIdByDocumentIds(@Param("documentIds") Collection<Long> documentIds);
}