package com.hai.aiknowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hai.aiknowledgebase.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 知识库文件表 Mapper
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /**
     * <h3>按 ID 查询文档（绕过逻辑删除过滤）</h3>
     *
     * <p>{@code @TableLogic} 仅对 MyBatis-Plus 内置方法生效，自定义 SQL 不会自动拼接
     * {@code is_deleted=0}，因此可查询到已逻辑删除的记录，供删除接口做幂等判断。</p>
     *
     * @param id 文档 ID
     * @return 文档实体（含已逻辑删除的记录，不存在返回 null）
     */
    @Select("SELECT * FROM knowledge_document WHERE id = #{id}")
    KnowledgeDocument selectByIdIncludeDeleted(@Param("id") Long id);

    /**
     * <h3>乐观更新文档向量化状态</h3>
     *
     * <p>仅当 {@code is_vectorized} 仍等于传入的旧值时写入，避免异步向量化线程与
     * 定时兜底任务并发聚合时发生「读-改-写」丢失更新。使用 {@code IS NOT DISTINCT FROM}
     * 做 NULL 安全比较，兼容旧值为 NULL（未触发）的场景。</p>
     *
     * @param documentId 文档 ID
     * @param newStatus  新的向量化状态
     * @param oldStatus  读取到的旧向量化状态（可为 NULL）
     * @return 受影响行数（0 表示已被其他线程抢先更新，本次跳过）
     */
    @Update("UPDATE knowledge_document SET is_vectorized = #{newStatus} " +
            "WHERE id = #{documentId} AND is_vectorized IS NOT DISTINCT FROM #{oldStatus}")
    int updateVectorStatusIfMatch(@Param("documentId") Long documentId,
                                  @Param("newStatus") Integer newStatus,
                                  @Param("oldStatus") Integer oldStatus);
}