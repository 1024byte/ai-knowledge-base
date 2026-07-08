package com.hai.aiknowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hai.aiknowledgebase.entity.ChatHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {

    /**
     * 获取每个会话的最后一条消息及最后活跃时间
     * 用于侧边栏会话列表展示
     */
    /**
     * 获取每个会话的最后一条消息及最后活跃时间
     * 用于侧边栏会话列表展示
     *
     * 当 userId 为 null 时，查询所有会话（匿名模式）
     * 当 userId 非 null 时，只查询该用户的会话
     */
    @Select("""
        <script>
        SELECT 
            session_id,
            MAX(create_time) as last_active,
            (SELECT content FROM chat_history c2 
             WHERE c2.session_id = c1.session_id 
             ORDER BY create_time DESC LIMIT 1) as last_content
        FROM chat_history c1
        <where>
            <if test="userId != null">
                user_id = #{userId}
            </if>
        </where>
        GROUP BY session_id
        ORDER BY last_active DESC
        LIMIT 50
        </script>
    """)
    List<Map<String, Object>> selectSessionList(@Param("userId") String userId);
}
