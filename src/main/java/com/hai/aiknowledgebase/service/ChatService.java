package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hai.aiknowledgebase.dto.ChatMessageDTO;
import com.hai.aiknowledgebase.dto.ChatSessionDTO;
import com.hai.aiknowledgebase.dto.HaiChatResponse;
import com.hai.aiknowledgebase.dto.QueryRewriteResult;
import com.hai.aiknowledgebase.dto.SearchResult;
import com.hai.aiknowledgebase.entity.ChatHistory;
import com.hai.aiknowledgebase.mapper.ChatHistoryMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.internal.Json;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;



@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final OpenAiChatModel chatModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatHistoryMapper chatHistoryMapper;  // ✅ 替换 JdbcTemplate

    // 缓存每个会话的 ChatMemory 实例
    private final Map<String, ChatMemory> chatMemoryCache = new ConcurrentHashMap<>();
    private final ChatMemoryStore chatMemoryStore;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RAGSearchService ragSearchService;  // 注入你的RAG服务
    private final QueryRewriteService queryRewriteService;  // 查询改写服务


    private static final String SYSTEM_PROMPT = """
        你是一个知识库助手，基于提供的文档内容回答用户问题。
        
        规则：
        1. 只基于提供的文档内容回答，不要编造信息
        2. 如果文档中没有相关信息，明确告知用户
        3. 回答要简洁、准确、有条理
        4. 可以适当引用文档中的原文
        """;

/*    public ChatService(OpenAiChatModel chatModel,
                       EmbeddingStore<TextSegment> embeddingStore,
                       EmbeddingModel embeddingModel,
                       ChatHistoryMapper chatHistoryMapper,  // ✅ 替换
                       ChatMemoryStore chatMemoryStore) {
        this.chatModel = chatModel;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatHistoryMapper = chatHistoryMapper;
        this.chatMemoryStore = chatMemoryStore;
    }*/

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

        // ===== 0. 查询改写 =====
        QueryRewriteResult rewriteResult = queryRewriteService.rewrite(question);
        String searchQuery = rewriteResult.getRewrittenQuery();
        List<String> expandKeywords = rewriteResult.getExpandKeywords();
        List<String> excludeKeywords = rewriteResult.getExcludeKeywords();
        log.info("查询改写 | 原始: {} | 改写: {} | 扩展词: {} | 排除词: {} | 置信度: {} | 路径: {}",
                question, searchQuery, expandKeywords, excludeKeywords,
                String.format("%.2f", rewriteResult.getConfidence()), rewriteResult.getPath());

        // ===== 1. 调用 RAGSearchService 检索相关片段（使用改写后的查询和扩展/排除关键词） =====
        List<TextSegment> segments = ragSearchService.retrieveSegments(searchQuery, topK, expandKeywords, excludeKeywords);
        log.info("检索到 {} 个相关文档片段", segments.size());

        // ===== 2. 构建上下文（与之前相同） =====
        String context = segments.stream()
                .map(TextSegment::text)
                .collect(Collectors.joining("\n\n---\n\n"));

        // ===== 3. 构建用户 Prompt =====
        String userPrompt = buildPrompt(context, question);

        // ===== 4. 获取当前会话的 ChatMemory =====
        ChatMemory chatMemory = getChatMemory(sessionId);

        // ===== 5. 构建完整消息列表（System + 历史 + 当前用户消息） =====
        List<ChatMessage> allMessages = new ArrayList<>();
        allMessages.add(SystemMessage.from(SYSTEM_PROMPT));
        allMessages.addAll(chatMemory.messages());
        allMessages.add(UserMessage.from(userPrompt));

        // ===== 6. 调用模型 =====
        ChatResponse llmResponse = chatModel.chat(allMessages);
        String answer = llmResponse.aiMessage().text();

        // ===== 7. 保存用户消息 =====
        saveMessage(sessionId, "user", question);

        // ===== 8. 更新 ChatMemory（保存用户问题和助手回复） =====
        chatMemory.add(UserMessage.from(userPrompt));
        chatMemory.add(AiMessage.from(answer));

        // ===== 9. 提取来源（从检索到的片段中获取 file_name 元数据） =====
        List<String> sources = segments.stream()
                .map(seg -> {
                    Object source = seg.metadata().getString("source");
                    return source != null ? source.toString() : null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // ===== 10. 保存助手消息（含来源） =====
        saveMessage(sessionId, "assistant", answer, sources);

        long processingTime = System.currentTimeMillis() - startTime;
        log.info("回答生成完成，耗时 {} ms", processingTime);

        return HaiChatResponse.builder()
                .sessionId(sessionId)
                .answer(answer)
                .sources(sources)
                .build();
    }

    // ==================== 消息保存（使用 MyBatis-Plus） ====================

    private void saveMessage(String sessionId, String role, String content) {
        // 原有方法保持不变，调用重载方法，sourceInfo 传 null
        saveMessage(sessionId, role, content, null);
    }
    /**
     * 保存单条消息到数据库
     */
    private void saveMessage(String sessionId, String role, String content,List<String> sources) {
        String sourceInfo = null;
        if (sources != null && !sources.isEmpty()) {
            sourceInfo = Json.toJson(sources);
        }
        ChatHistory record = new ChatHistory();
        record.setSessionId(sessionId);
        record.setRole(role);
        record.setContent(content);
        record.setSourceInfo(sourceInfo);
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
                        record.getCreateTime(),
                        getStringListFromChat(record)
                ))
                .collect(Collectors.toList());
    }
    public static List<String> getStringListFromChat(ChatHistory chatHistory) {
        if (chatHistory == null) {
            return Collections.emptyList();
        }
        String json = chatHistory.getSourceInfo();
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // 解析出错返回空集合，避免业务报错
            return Collections.emptyList();
        }
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
        //使用 MyBatis-Plus 删除
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