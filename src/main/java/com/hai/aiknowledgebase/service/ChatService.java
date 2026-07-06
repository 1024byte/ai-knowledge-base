package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.HaiChatResponse;
import com.hai.aiknowledgebase.dto.SearchResult;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate; // 用于手动记录完整消息

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

    // 构造器注入 ChatMemoryStore
    public ChatService(OpenAiChatModel chatModel,
                       EmbeddingStore<TextSegment> embeddingStore,
                       EmbeddingModel embeddingModel,
                       JdbcTemplate jdbcTemplate,
                       ChatMemoryStore chatMemoryStore) {
        this.chatModel = chatModel;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
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

        // 1. 向量检索（保持不变）
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

        // 4. 构建消息列表：系统消息 + 历史消息 + 当前用户消息
        List<ChatMessage> allMessages = new ArrayList<>();
        allMessages.add(SystemMessage.from(SYSTEM_PROMPT));
        allMessages.addAll(chatMemory.messages());  // ✅ 使用 messages() 获取历史
        allMessages.add(UserMessage.from(userPrompt));

        // 5. 调用模型生成回答
        ChatResponse llmResponse = chatModel.chat(allMessages);
        String answer = llmResponse.aiMessage().text();

        // 6. 保存消息到数据库（用于审计）
        saveMessage(sessionId, "user", question);
        saveMessage(sessionId, "assistant", answer);

        // 7. 更新 ChatMemory（添加本次的消息对）
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

    // 保存单条消息到数据库
    private void saveMessage(String sessionId, String role, String content) {
        String sql = "INSERT INTO chat_history (session_id, role, content) VALUES (?, ?, ?)";
        jdbcTemplate.update(sql, sessionId, role, content);
    }

    private String buildPrompt(String context, String question) {
        return String.format("""
            基于以下文档内容回答问题：
            
            %s
            
            问题：%s
            
            请基于上述文档内容回答，如果文档中没有相关信息，请明确告知"根据提供的文档，没有找到相关信息"。
            """, context, question);
    }

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
            .map(match -> new SearchResult(
                match.embedded().text(),
                match.score(),
                match.embedded().metadata().getString("source") == null ? "未知来源" : match.embedded().metadata().getString("source").toString()
            ))
            .collect(Collectors.toList());
    }
}
