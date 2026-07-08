package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hai.aiknowledgebase.dto.ChatMessageDTO;
import com.hai.aiknowledgebase.dto.ChatSessionDTO;
import com.hai.aiknowledgebase.dto.HaiChatResponse;
import com.hai.aiknowledgebase.dto.SearchResult;
import com.hai.aiknowledgebase.entity.ChatHistory;
import com.hai.aiknowledgebase.mapper.ChatHistoryMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService {

    private final OpenAiChatModel chatModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatHistoryMapper chatHistoryMapper;  // ✅ 替换 JdbcTemplate

    // 缓存每个会话的 ChatMemory 实例
    private final Map<String, ChatMemory> chatMemoryCache = new ConcurrentHashMap<>();
    private final ChatMemoryStore chatMemoryStore;

    private static final String SYSTEM_PROMPT = """
        你是一个知识库助手，基于提供的文档内容回答用户问题。
        
        规则：
        1. 只基于提供的文档内容回答，不要编造信息
        2. 如果文档中没有相关信息，明确告知用户
        3. 回答要简洁、准确、有条理
        4. 可以适当引用文档中的原文
        """;

    public ChatService(OpenAiChatModel chatModel,
                       EmbeddingStore<TextSegment> embeddingStore,
                       EmbeddingModel embeddingModel,
                       ChatHistoryMapper chatHistoryMapper,  // ✅ 替换
                       ChatMemoryStore chatMemoryStore) {
        this.chatModel = chatModel;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatHistoryMapper = chatHistoryMapper;
        this.chatMemoryStore = chatMemoryStore;
    }

    // 获取或创建会话的 ChatMemory
    private ChatMemory getChatMemory(String sessionId) {
        return chatMemoryCache.computeIfAbsent(sessionId, id ->
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build()
        );
    }

    public HaiChatResponse chat(String sessionId, String question, int topK) {
        long startTime = System.currentTimeMillis();

        // 1. 向量检索
        Response<Embedding> embeddingResponse = embeddingModel.embed(question);
        Embedding questionEmbedding = embeddingResponse.content();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(questionEmbedding)
                        .maxResults(topK)
                        .build()
        );
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        log.info("检索到 {} 个相关文档片段", matches.size());

        String context = matches.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n---\n\n"));

        // 2. 构建提示词
        String userPrompt = buildPrompt(context, question);

        // 3. 获取当前会话的 ChatMemory
        ChatMemory chatMemory = getChatMemory(sessionId);

        // 4. 构建消息列表
        List<ChatMessage> allMessages = new ArrayList<>();
        allMessages.add(SystemMessage.from(SYSTEM_PROMPT));
        allMessages.addAll(chatMemory.messages());
        allMessages.add(UserMessage.from(userPrompt));

        // 5. 调用模型
        ChatResponse llmResponse = chatModel.chat(allMessages);
        String answer = llmResponse.aiMessage().text();

        // 6.使用 MyBatis-Plus 保存消息
        saveMessage(sessionId, "user", question);
        saveMessage(sessionId, "assistant", answer);

        // 7. 更新 ChatMemory
        chatMemory.add(UserMessage.from(userPrompt));
        chatMemory.add(AiMessage.from(answer));

        // 8. 提取来源
        List<String> sources = matches.stream()
                .map(match -> {
                    Object source = match.embedded().metadata().getString("source");
                    return source != null ? source.toString() : null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        long processingTime = System.currentTimeMillis() - startTime;
        log.info("回答生成完成，耗时 {} ms", processingTime);

        return new HaiChatResponse(answer, sources, processingTime);
    }

    // ==================== 消息保存（使用 MyBatis-Plus） ====================

    /**
     * 保存单条消息到数据库
     */
    private void saveMessage(String sessionId, String role, String content) {
        ChatHistory record = new ChatHistory();
        record.setSessionId(sessionId);
        record.setRole(role);
        record.setContent(content);
        // userId 暂时为 null（匿名模式），后续接入用户系统后可设置
        chatHistoryMapper.insert(record);
    }

    // ==================== 会话历史查询（使用 MyBatis-Plus） ====================

    /**
     * 获取某个会话的历史消息
     */
    public List<ChatMessageDTO> getHistory(String sessionId) {
        LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatHistory::getSessionId, sessionId)
                .orderByAsc(ChatHistory::getCreateTime);

        List<ChatHistory> list = chatHistoryMapper.selectList(wrapper);

        return list.stream()
                .map(record -> new ChatMessageDTO(
                        record.getRole(),
                        record.getContent(),
                        record.getCreateTime()
                ))
                .collect(Collectors.toList());
    }

    /**
     * 获取当前用户的所有会话列表
     * @param userId 如果为 null，则查询所有会话（匿名模式）
     */
    public List<ChatSessionDTO> getSessions(String userId) {
        // ✅ 使用 Mapper 中的自定义方法（XML 或注解 SQL）
        List<Map<String, Object>> rows = chatHistoryMapper.selectSessionList(userId);

        return rows.stream()
                .map(row -> {
                    String sessionId = String.valueOf(row.get("session_id"));
                    String lastContent = row.get("last_content") != null
                            ? String.valueOf(row.get("last_content"))
                            : null;
                    String preview = lastContent != null && lastContent.length() > 20
                            ? lastContent.substring(0, 20) + "..."
                            : lastContent;

                    Object lastActive = row.get("last_active");
                    java.time.LocalDateTime lastActiveTime = lastActive != null
                            ? ((java.sql.Timestamp) lastActive).toLocalDateTime()
                            : null;

                    return new ChatSessionDTO(
                            sessionId,
                            preview != null ? preview : "空会话",
                            lastActiveTime
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * 删除某个会话及其所有消息
     */
    public void deleteSession(String sessionId) {
        // ✅ 使用 MyBatis-Plus 删除
        LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatHistory::getSessionId, sessionId);
        chatHistoryMapper.delete(wrapper);

        // 清除内存中的 ChatMemory
        chatMemoryCache.remove(sessionId);
        // 清除 ChatMemoryStore 中的记录
        chatMemoryStore.deleteMessages(sessionId);

        log.info("已删除会话: {}", sessionId);
    }

    // ==================== 搜索接口（不变） ====================

    public List<SearchResult> search(String query, int topK) {
        log.info("搜索查询: {}", query);

        Response<Embedding> queryEmbeddingResponse = embeddingModel.embed(query);
        Embedding queryEmbedding = queryEmbeddingResponse.content();

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

        return searchResult.matches().stream()
                .map(match -> {
                    Object source = match.embedded().metadata().getString("source");
                    return new SearchResult(
                            match.embedded().text(),
                            match.score(),
                            source != null ? source.toString() : "未知来源"
                    );
                })
                .collect(Collectors.toList());
    }

    // ==================== 辅助方法 ====================

    private String buildPrompt(String context, String question) {
        return String.format("""
            基于以下文档内容回答问题：
            
            %s
            
            问题：%s
            
            请基于上述文档内容回答，如果文档中没有相关信息，请明确告知"根据提供的文档，没有找到相关信息"。
            """, context, question);
    }
}