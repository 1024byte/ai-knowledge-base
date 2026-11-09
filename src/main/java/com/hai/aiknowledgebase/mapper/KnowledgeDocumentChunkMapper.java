package com.hai.aiknowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hai.aiknowledgebase.entity.KnowledgeDocumentChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * <h2>文档切片表 Mapper</h2>
 *
 * <p>提供切片的基本 CRUD 操作，包括根据 documentId 全量删除（用于覆盖写入）。</p>
 */
@Mapper
public interface KnowledgeDocumentChunkMapper extends BaseMapper<KnowledgeDocumentChunk> {
}