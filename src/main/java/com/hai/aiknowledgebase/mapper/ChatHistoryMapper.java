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
     * 获取每个会话的最后一条消息、最后活跃时间及代表知识库 ID（kb_id）
     * 用于侧边栏会话列表展示。
     *
     * 仅查询指定用户的会话；kb_id 取该会话下最新一条非空 kb_id，
     * 供上层按知识库可见性过滤（S7 会话隔离）。
     */
    @Select("""
        <script>
        SELECT 
            session_id,
            MAX(create_time) as last_active,
            (SELECT content FROM chat_history c2 
             WHERE c2.session_id = c1.session_id 
             ORDER BY create_time DESC LIMIT 1) as last_content,
            (SELECT kb_id FROM chat_history c3 
             WHERE c3.session_id = c1.session_id AND c3.kb_id IS NOT NULL 
             ORDER BY create_time DESC LIMIT 1) as kb_id
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