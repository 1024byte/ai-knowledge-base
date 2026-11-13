package com.hai.aiknowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hai.aiknowledgebase.entity.KbMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库成员 Mapper
 */
@Mapper
public interface KbMemberMapper extends BaseMapper<KbMember> {
}