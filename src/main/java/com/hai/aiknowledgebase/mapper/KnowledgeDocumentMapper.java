package com.hai.aiknowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hai.aiknowledgebase.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}