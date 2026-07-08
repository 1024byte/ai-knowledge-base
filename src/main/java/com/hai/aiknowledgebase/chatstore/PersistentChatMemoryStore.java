package com.hai.aiknowledgebase.chatstore;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hai.aiknowledgebase.entity.ChatHistory;
import com.hai.aiknowledgebase.mapper.ChatHistoryMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PersistentChatMemoryStore implements ChatMemoryStore {

    private final ChatHistoryMapper chatHistoryMapper;  // ✅ 替换 JdbcTemplate

    // 最大保留消息数量（防止 token 溢出）
    private static final int MAX_HISTORY = 20;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.debug("加载会话历史: {}", sessionId);

        // 使用 MyBatis-Plus 查询
        LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatHistory::getSessionId, sessionId)
                .orderByAsc(ChatHistory::getCreateTime)
                .last("LIMIT " + MAX_HISTORY);  // 限制返回条数

        List<ChatHistory> records = chatHistoryMapper.selectList(wrapper);

        // 转换为 LangChain4j 的 ChatMessage 列表
        return records.stream()
                .map(record -> {
                    String role = record.getRole();
                    String content = record.getContent();
                    return "user".equals(role)
                            ? UserMessage.from(content)
                            : AiMessage.from(content);
                })
                .collect(Collectors.toList());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // ChatMemory 会在此方法中要求更新存储，但我们采用"追加写入"策略
        // 因为历史消息不应该被覆盖，所以留空，由业务层手动保存
        // 但为了兼容框架，可以不做任何操作
        log.debug("updateMessages 被调用，但采用追加写入策略，忽略此操作");
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.info("清空会话历史: {}", sessionId);
        // 使用 MyBatis-Plus 删除
        LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatHistory::getSessionId, sessionId);
        chatHistoryMapper.delete(wrapper);
    }
}