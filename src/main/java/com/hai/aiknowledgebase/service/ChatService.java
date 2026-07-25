package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hai.aiknowledgebase.dto.*;
import com.hai.aiknowledgebase.entity.ChatHistory;
import com.hai.aiknowledgebase.mapper.ChatHistoryMapper;
import com.hai.aiknowledgebase.queryrewrite.QueryRewriteService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.internal.Json;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;



@Slf4j
@Service
public class ChatService {

    private final OpenAiChatModel chatModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatHistoryMapper chatHistoryMapper;
    private final Cache<String, ChatMemory> chatMemoryCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .removalListener((String key, ChatMemory value, RemovalCause cause) ->
                    log.debug("ChatMemory 缓存过期: sessionId={}, cause={}", key, cause))
            .build();
    private final ChatMemoryStore chatMemoryStore;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final RAGSearchService ragSearchService;
    private final QueryRewriteService queryRewriteService;
    private final TokenCountEstimator tokenCountEstimator;

    @Value("${chat.prompt.max-context-tokens:3000}")
    private int maxContextTokens;

    @Value("${chat.prompt.max-history-tokens:1500}")
    private int maxHistoryTokens;

    @Value("${chat.prompt.max-assistant-history-chars:100}")
    private int maxAssistantHistoryChars;

    private static final String SYSTEM_PROMPT = """
            基于下方【参考资料】回答【问题】。规则：
            1. 仅依据参考资料作答，不编造
            2. 资料中无相关信息时回复"根据提供的文档，没有找到相关信息"
            3. 回答简洁、准确、有条理
            """;

    public ChatService(OpenAiChatModel chatModel,
                       EmbeddingStore<TextSegment> embeddingStore,
                       EmbeddingModel embeddingModel,
                       ChatHistoryMapper chatHistoryMapper,
                       ChatMemoryStore chatMemoryStore,
                       RAGSearchService ragSearchService,
                       QueryRewriteService queryRewriteService,
                       TokenCountEstimator tokenCountEstimator) {
        this.chatModel = chatModel;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatHistoryMapper = chatHistoryMapper;
        this.chatMemoryStore = chatMemoryStore;
        this.ragSearchService = ragSearchService;
        this.queryRewriteService = queryRewriteService;
        this.tokenCountEstimator = tokenCountEstimator;
    }


    // 获取或创建会话的 ChatMemory（Caffeine 自动管理过期和淘汰）
    private ChatMemory getChatMemory(String sessionId) {
        return chatMemoryCache.get(sessionId, id ->
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build()
        );
    }

    /**
     * 从 ChatMemory 中提取截断后的对话历史（最近 2 轮）
     *
     * <p>截断规则：
     * <ul>
     *   <li>仅取最近 4 条消息（2 轮对话 = 2 user + 2 assistant）</li>
     *   <li>assistant 消息截断至 200 字，避免过长 Prompt 影响 L3 改写效率</li>
     *   <li>user 消息保留完整内容</li>
     * </ul>
     *
     * <p>如果 ChatMemory 为空或无历史消息，返回空列表。</p>
     *
     * @param chatMemory 当前会话的 ChatMemory 实例
     * @return 截断后的对话历史列表（最近 2 轮，最多 4 条）
     */
    private List<CustomChatMessage> extractTruncatedHistory(ChatMemory chatMemory) {
        if (chatMemory == null) {
            return Collections.emptyList();
        }
        List<ChatMessage> allMessages = chatMemory.messages();
        if (allMessages == null || allMessages.isEmpty()) {
            return Collections.emptyList();
        }

        // 取最近 4 条消息（2 轮对话）
        int start = Math.max(0, allMessages.size() - 4);
        List<CustomChatMessage> result = new ArrayList<>();
        for (int i = start; i < allMessages.size(); i++) {
            ChatMessage msg = allMessages.get(i);
            String role;
            String content;
            if (msg instanceof AiMessage) {
                role = "assistant";
                content = ((AiMessage) msg).text();
            } else if (msg instanceof UserMessage) {
                role = "user";
                content = ((UserMessage) msg).singleText();
            } else {
                role = "system";
                content = ((SystemMessage) msg).text();
            }

            if (msg instanceof AiMessage && content != null && content.length() > maxAssistantHistoryChars) {
                content = content.substring(0, maxAssistantHistoryChars);
            }

            result.add(CustomChatMessage.builder()
                    .role(role)
                    .content(content != null ? content : "")
                    .build());
        }
        return result;
    }

    /**
     * RAG 问答核心方法：检索 → 增强 → 生成
     *
     * <h3>处理流程（8 步）</h3>
     * <ol start="0">
     *   <li><b>查询改写</b>：指代消解 + 同义词扩展 + 固定映射 + 意图识别，生成改写查询 + 扩展/排除关键词</li>
     *   <li><b>混合检索</b>：调用 {@link RAGSearchService#retrieveSegments} 进行向量+BM25双路检索</li>
     *   <li><b>构建上下文</b>：将检索到的文档片段拼接为 LLM 上下文（以 "---" 分隔）</li>
     *   <li><b>构建 Prompt</b>：将上下文和用户问题组装为完整提示词</li>
     *   <li><b>构建消息列表</b>：System提示词 + 历史消息 + 当前用户Prompt → LLM 输入</li>
     *   <li><b>调用 LLM</b>：发送消息列表，获取 AI 回答</li>
     *   <li><b>提取来源</b>：从检索片段的 metadata 中提取 source 字段，去重后作为引用来源</li>
     *   <li><b>原子写入</b>：DB（user + assistant 消息）+ ChatMemory 同步保存，失败时回滚</li>
     * </ol>
     *
     * <h3>多轮对话支持</h3>
     * 通过 {@code sessionId} 关联同一会话的多轮对话。ChatMemory 缓存最近 20 条消息，
     * 30 分钟无访问自动过期。首次对话时从 DB 加载历史消息初始化 ChatMemory。
     *
     * <h3>查询改写（含指代消解）</h3>
     * 在检索前对用户原始问题进行改写，包括：
     * <ul>
     *   <li><b>指代消解</b>：从最近 2 轮对话历史中提取实体，将"它/这个/上面"等指代词替换为具体实体</li>
     *   <li>同义词扩展（如 "年假" → 同时检索 "年假"、"带薪休假"）</li>
     *   <li>固定映射（如 "API" → "API 接口"）</li>
     *   <li>意图识别（规则引擎 + LLM 兜底，区分定义/操作/对比/事实类问题）</li>
     * </ul>
     *
     * @param sessionId 会话 ID，由前端生成（如 "user-001"），用于关联多轮对话历史
     * @param question  用户原始问题，不可为空
     * @param topK      检索返回的最大文档片段数，建议 3~10
     * @return 包含 AI 回答、来源引用和会话 ID 的响应对象
     */
    public HaiChatResponse chat(String sessionId, String question, int topK) {
        long startTime = System.currentTimeMillis();

        // 步骤0：查询改写（含指代消解，使用对话历史）
        // 0.1 获取当前会话的 ChatMemory，提取最近 2 轮对话历史
        ChatMemory chatMemory = getChatMemory(sessionId);
        List<CustomChatMessage> truncatedHistory = extractTruncatedHistory(chatMemory);

        // 0.2 构建改写请求（携带对话历史，用于指代消解和按需路由）
        RewriteRequest rewriteRequest = RewriteRequest.builder()
                .query(question)
                .truncatedHistory(truncatedHistory)
                .sessionId(sessionId)
                .build();
        QueryRewriteResult rewriteResult = queryRewriteService.rewrite(rewriteRequest);

        String searchQuery = rewriteResult.getRewrittenQuery();//意图词
        List<String> expandKeywords = rewriteResult.getExpandKeywords();
        List<String> excludeKeywords = rewriteResult.getExcludeKeywords();
        String hypotheticAnswer = rewriteResult.getHypotheticAnswer();
        List<String> subQueries = rewriteResult.getSubQueries();
        log.info("查询改写 | 原始: {} | 改写: {} | 扩展词: {} | 排除词: {} | 置信度: {} | 路径: {} | 策略: {}",
                question, searchQuery, expandKeywords, excludeKeywords,
                String.format("%.2f", rewriteResult.getConfidence()), rewriteResult.getPath(),
                rewriteResult.getStrategy());

        // 步骤1：混合检索（向量语义 + BM25 关键词，RRF 融合排序）
        List<TextSegment> segments = ragSearchService.retrieveSegments(searchQuery, topK, expandKeywords, excludeKeywords);
        log.info("检索到 {} 个相关文档片段", segments.size());

        // 步骤1.1：检索质量检查，不达标时触发 HyDE 兜底
        if (isRetrievalQualityPoor(segments)) {
            log.warn("检索质量不达标 (结果数={}, 最高分={})，触发 HyDE 兜底",
                    segments.size(), getTopScore(segments));

            // 如果改写结果中已有 HyDE 答案，直接使用；否则重新生成
            if (hypotheticAnswer == null || hypotheticAnswer.isBlank()) {
                hypotheticAnswer = generateHydeAnswer(question);
            }

            if (hypotheticAnswer != null && !hypotheticAnswer.isBlank()) {
                log.info("HyDE 兜底检索: 使用假设性答案进行语义检索");
                List<TextSegment> hydeSegments = ragSearchService.retrieveSegments(
                        hypotheticAnswer, topK, expandKeywords, excludeKeywords);
                if (!hydeSegments.isEmpty()) {
                    segments = hydeSegments;
                    log.info("HyDE 检索获得 {} 个补充片段", hydeSegments.size());
                }
            }
        }

        // 步骤1.2：如果存在子查询（DECOMPOSE 策略），并行检索子查询结果
        if (subQueries != null && !subQueries.isEmpty()) {
            log.info("执行子查询检索: {} 个子查询", subQueries.size());
            for (String subQuery : subQueries) {
                try {
                    List<TextSegment> subSegments = ragSearchService.retrieveSegments(
                            subQuery, Math.max(2, topK / subQueries.size()), expandKeywords, excludeKeywords);
                    segments.addAll(subSegments);
                } catch (Exception e) {
                    log.warn("子查询检索失败: {} -> {}", subQuery, e.getMessage());
                }
            }
            // 去重：按文本内容去重
            segments = segments.stream()
                    .collect(Collectors.toMap(
                            TextSegment::text,
                            s -> s,
                            (s1, s2) -> s1))
                    .values().stream()
                    .limit(topK)
                    .collect(Collectors.toList());
        }

        // 步骤2：构建上下文（Token 预算制：按片段顺序填入，超出预算截断或丢弃）
        ContextBuildResult contextResult = buildContextWithinBudget(segments, maxContextTokens);
        String context = contextResult.context();
        log.info("上下文构建: {}/{} 个片段使用, {}/{} token 预算",
                contextResult.usedSegments(), segments.size(),
                contextResult.usedTokens(), maxContextTokens);

        // 步骤3：构建用户 Prompt（精简模板：参考资料 + 问题）
        String userPrompt = buildPrompt(context, question);

        // 步骤4：构建完整消息列表（System提示词 + Token 预算内历史消息 + 当前用户Prompt）
        List<ChatMessage> historyMessages = truncateHistoryByTokenBudget(
                chatMemory.messages(), maxHistoryTokens);
        List<ChatMessage> allMessages = new ArrayList<>();
        allMessages.add(SystemMessage.from(SYSTEM_PROMPT));
        allMessages.addAll(historyMessages);
        allMessages.add(UserMessage.from(userPrompt));

        int totalTokens = estimateMessagesTokenCount(allMessages);
        log.info("LLM 输入: 历史消息={}, 上下文={}/{}, 总估算 token={}",
                historyMessages.size(), contextResult.usedTokens(), maxContextTokens, totalTokens);
        // 步骤5：调用 LLM 生成回答
        ChatResponse llmResponse = chatModel.chat(allMessages);
        String answer = llmResponse.aiMessage().text();

        // 步骤6：提取来源引用（从检索片段的 metadata.source 去重）
        List<String> sources = segments.stream()
                .map(seg -> {
                    Object source = seg.metadata().getString("source");
                    return source != null ? source.toString() : null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // 步骤7：原子写入 DB + ChatMemory（失败时回滚 DB 中的 user 消息）
        saveMessageWithMemory(sessionId, question, userPrompt, answer, sources, chatMemory);

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

    /**
     * <h3>原子写入：DB + ChatMemory 同步保存</h3>
     *
     * <p>将 DB 写入（user + assistant 两条消息）和 ChatMemory 写入（UserMessage + AiMessage）
     * 包装在同一事务中。如果 DB 写入成功但 ChatMemory 写入失败，回滚 DB 中已写入的消息，
     * 避免 ChatMemory 下次从 DB 重建时出现单边消息（只有 user 没有 assistant）。</p>
     *
     * <h4>回滚策略</h4>
     * <ul>
     *   <li>DB 写入 user 消息成功 → 写入 ChatMemory → 失败 → 回滚 DB 中 user 消息</li>
     *   <li>DB 写入 user 消息成功 → ChatMemory 写入成功 → DB 写入 assistant 失败 → 回滚全部</li>
     * </ul>
     *
     * <p>注：这不是严格的 ACID 事务（跨 DB 和内存），但通过回滚 DB 记录保证了最终一致性。</p>
     */
    private void saveMessageWithMemory(String sessionId, String question, String userPrompt,
                                        String answer, List<String> sources, ChatMemory chatMemory) {
        // 阶段1：写入 DB（user 消息）
        saveMessage(sessionId, "user", question);
        try {
            // 阶段2：写入 ChatMemory（user + assistant）
            chatMemory.add(UserMessage.from(userPrompt));
            chatMemory.add(AiMessage.from(answer));

            // 阶段3：写入 DB（assistant 消息）
            saveMessage(sessionId, "assistant", answer, sources);
        } catch (Exception e) {
            // 阶段4：回滚——删除 DB 中已写入的 user 消息
            log.warn("记忆写入失败，回滚 DB 中的 user 消息: sessionId={}", sessionId, e);
            try {
                LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(ChatHistory::getSessionId, sessionId)
                        .eq(ChatHistory::getRole, "user")
                        .orderByDesc(ChatHistory::getCreateTime)
                        .last("LIMIT 1");
                ChatHistory latest = chatHistoryMapper.selectOne(wrapper);
                if (latest != null) {
                    chatHistoryMapper.deleteById(latest.getId());
                }
            } catch (Exception rollbackEx) {
                log.error("回滚失败，DB 中可能存在孤立 user 消息: sessionId={}", sessionId, rollbackEx);
            }
            throw new RuntimeException("记忆写入失败", e);
        }
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
        chatMemoryCache.invalidate(sessionId);
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
                【参考资料】
                %s

                【问题】%s""", context, question);
    }

    // ==================== 检索质量检查 ====================

    /** 检索质量最低分阈值，低于此分数视为质量不达标 */
    private static final double MIN_RETRIEVAL_SCORE = 0.65;

    /** 检索结果最小数量阈值 */
    private static final int MIN_RETRIEVAL_COUNT = 3;

    /**
     * 检索质量检查：判断检索结果是否达标
     *
     * <p>不达标条件：</p>
     * <ul>
     *   <li>结果数 < 3 且最高分 < 0.65（有结果但质量差，HyDE 可救）</li>
     *   <li>注意：空结果 ≠ 质量差，说明知识库无相关内容，不触发 HyDE 避免浪费 token</li>
     * </ul>
     */
    private boolean isRetrievalQualityPoor(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return false;
        }
        if (segments.size() < MIN_RETRIEVAL_COUNT && getTopScore(segments) < MIN_RETRIEVAL_SCORE) {
            return true;
        }
        return false;
    }

    /**
     * 获取检索结果中的最高相似度得分
     */
    private double getTopScore(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return 0.0;
        }
        return segments.stream()
                .mapToDouble(seg -> {
                    try {
                        return seg.metadata().getDouble("score");
                    } catch (Exception e) {
                        return 0.0;
                    }
                })
                .max()
                .orElse(0.0);
    }

    /**
     * 生成 HyDE 假设性答案
     *
     * <p>当检索质量不达标时，让 LLM 生成一段假设性知识片段，
     * 用此片段进行语义检索，找到真正相关的文档。</p>
     */
    private String generateHydeAnswer(String query) {
        try {
            String prompt = String.format("""
                    请根据以下问题，假设你是一个知识库，生成一段简短的回答（100-200字）。
                    即使你不确定，也请写出一个合理的假设性回答。
                    这个回答将用于语义检索，而不是直接展示给用户。
                    只返回回答内容，不要任何解释。

                    问题：%s
                    假设性回答：""", query);

            return chatModel.chat(
                    SystemMessage.from("你是一个知识库助手。根据问题生成一段假设性回答用于检索。"),
                    UserMessage.from(prompt)
            ).aiMessage().text();
        } catch (Exception e) {
            log.warn("HyDE 生成失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Token 预算控制方法 ====================

    /**
     * <h3>上下文 Token 预算制构建</h3>
     *
     * <p>按片段顺序逐个填入上下文，累计 token 不超过预算。
     * 最后一个片段如果超出剩余预算，截断到剩余 token 数而非整段丢弃。</p>
     *
     * @param segments       检索到的文档片段列表
     * @param maxTokens      token 预算上限
     * @return 上下文构建结果（包含拼接文本、使用片段数、使用 token 数）
     */
    private ContextBuildResult buildContextWithinBudget(List<TextSegment> segments, int maxTokens) {
        if (segments == null || segments.isEmpty()) {
            return new ContextBuildResult("", 0, 0);
        }

        StringBuilder contextBuilder = new StringBuilder();
        int usedTokens = 0;
        int usedSegments = 0;
        String separator = "\n\n---\n\n";
        int separatorTokens = tokenCountEstimator.estimateTokenCountInText(separator);

        for (int i = 0; i < segments.size(); i++) {
            String text = segments.get(i).text();
            int textTokens = tokenCountEstimator.estimateTokenCountInText(text);
            int totalAddTokens = textTokens + (i > 0 ? separatorTokens : 0);

            if (usedTokens + totalAddTokens <= maxTokens) {
                if (i > 0) {
                    contextBuilder.append(separator);
                }
                contextBuilder.append(text);
                usedTokens += totalAddTokens;
                usedSegments++;
            } else {
                int remainingTokens = maxTokens - usedTokens - (i > 0 ? separatorTokens : 0);
                if (remainingTokens > 50) {
                    if (i > 0) {
                        contextBuilder.append(separator);
                    }
                    String truncated = truncateByTokenBudget(text, remainingTokens);
                    contextBuilder.append(truncated);
                    usedTokens += tokenCountEstimator.estimateTokenCountInText(truncated)
                            + (i > 0 ? separatorTokens : 0);
                    usedSegments++;
                }
                break;
            }
        }

        return new ContextBuildResult(contextBuilder.toString(), usedSegments, usedTokens);
    }

    /**
     * <h3>按 Token 预算截断文本</h3>
     *
     * <p>从文本中逐字符累加，直到 token 数接近预算上限。
     * 截断位置优先选择句子边界（句号、问号、感叹号、换行符），
     * 避免在词语中间截断导致语义不完整。</p>
     *
     * @param text      原始文本
     * @param maxTokens token 预算上限
     * @return 截断后的文本
     */
    private String truncateByTokenBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        int currentTokens = 0;
        int lastSentenceEnd = -1;
        for (int i = 0; i < text.length(); i++) {
            currentTokens = tokenCountEstimator.estimateTokenCountInText(text.substring(0, i + 1));
            if (currentTokens > maxTokens) {
                if (lastSentenceEnd > 0) {
                    return text.substring(0, lastSentenceEnd + 1);
                }
                return text.substring(0, i);
            }
            char c = text.charAt(i);
            if (c == '。' || c == '？' || c == '！' || c == '.' || c == '?' || c == '!'
                    || c == '\n' || c == '；' || c == ';') {
                lastSentenceEnd = i;
            }
        }
        return text;
    }

    /**
     * <h3>按 Token 预算截断历史消息</h3>
     *
     * <p>从历史消息列表尾部向前遍历，累加 token 不超过预算。
     * 超出预算时停止，只保留最近的高质量历史消息。
     * assistant 消息在截断前先压缩到 {@code maxAssistantHistoryChars} 字符。</p>
     *
     * @param messages  完整的历史消息列表
     * @param maxTokens token 预算上限
     * @return 截断后的历史消息列表
     */
    private List<ChatMessage> truncateHistoryByTokenBudget(List<ChatMessage> messages, int maxTokens) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChatMessage> result = new ArrayList<>();
        int usedTokens = 0;

        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            String content = extractMessageText(msg);

            if (msg instanceof AiMessage && content != null && content.length() > maxAssistantHistoryChars) {
                content = content.substring(0, maxAssistantHistoryChars);
                msg = AiMessage.from(content);
            }

            int msgTokens = tokenCountEstimator.estimateTokenCountInText(content != null ? content : "");
            if (usedTokens + msgTokens > maxTokens) {
                break;
            }
            usedTokens += msgTokens;
            result.add(0, msg);
        }

        return result;
    }

    /**
     * 提取 ChatMessage 的文本内容
     */
    private String extractMessageText(ChatMessage msg) {
        if (msg instanceof AiMessage) {
            return ((AiMessage) msg).text();
        } else if (msg instanceof UserMessage) {
            return ((UserMessage) msg).singleText();
        } else if (msg instanceof SystemMessage) {
            return ((SystemMessage) msg).text();
        }
        return "";
    }

    /**
     * 估算消息列表的总 token 数
     */
    private int estimateMessagesTokenCount(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += tokenCountEstimator.estimateTokenCountInText(extractMessageText(msg));
        }
        return total;
    }

    /**
     * 上下文构建结果
     */
    private record ContextBuildResult(String context, int usedSegments, int usedTokens) {
    }
}