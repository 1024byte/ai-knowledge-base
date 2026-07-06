package com.hai.aiknowledgebase.chatstore;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PersistentChatMemoryStore implements ChatMemoryStore {


    private final JdbcTemplate jdbcTemplate;

    // 最大保留消息数量（防止 token 溢出）
    private static final int MAX_HISTORY = 20;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.debug("加载会话历史: {}", sessionId);

        String sql = """
                SELECT role, content 
                FROM chat_history 
                WHERE session_id = ? 
                ORDER BY create_time ASC 
                LIMIT ?
                """;

        return jdbcTemplate.query(sql, new Object[]{sessionId, MAX_HISTORY}, (rs, rowNum) -> {
            String role = rs.getString("role");
            String content = rs.getString("content");
            return "user".equals(role)
                    ? UserMessage.from(content)
                    : AiMessage.from(content);
        });
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // ChatMemory 会在此方法中要求更新存储，但我们采用"追加写入"策略
        // 因为历史消息不应该被覆盖，所以留空，由业务层手动保存
        // 但为了兼容框架，可以不做任何操作
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.info("清空会话历史: {}", sessionId);
        jdbcTemplate.update("DELETE FROM chat_history WHERE session_id = ?", sessionId);
    }
}
