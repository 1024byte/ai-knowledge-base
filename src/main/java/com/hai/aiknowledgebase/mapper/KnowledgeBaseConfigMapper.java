package com.hai.aiknowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hai.aiknowledgebase.entity.KnowledgeBaseConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库高级配置表 Mapper
 */
@Mapper
public interface KnowledgeBaseConfigMapper extends BaseMapper<KnowledgeBaseConfig> {
}