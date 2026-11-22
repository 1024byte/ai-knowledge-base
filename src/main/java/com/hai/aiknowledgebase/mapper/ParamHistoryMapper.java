package com.hai.aiknowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hai.aiknowledgebase.entity.ParamHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 参数操作历史 Mapper。
 */
@Mapper
public interface ParamHistoryMapper extends BaseMapper<ParamHistory> {
}