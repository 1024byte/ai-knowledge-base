package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hai.aiknowledgebase.annotation.Timed;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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
    private final ThreadPoolTaskExecutor taskExecutor;

    @Value("${chat.prompt.max-context-tokens:3000}")
    private int maxContextTokens;

    @Value("${chat.prompt.max-history-tokens:1500}")
    private int maxHistoryTokens;

    @Value("${chat.prompt.max-assistant-history-chars:100}")
    private int maxAssistantHistoryChars;

    @Value("${chat.retrieval.top-k:15}")
    private int topK;

    private static final String SYSTEM_PROMPT = """
            你是一个严格基于文档的问答助手。请根据下方【参考资料】回答【问题】。

            ## 核心规则
            1. **仅依据参考资料作答**：每一个结论、数据、事实都必须能在参考资料中找到原文依据
            2. **禁止使用外部知识**：不得引入参考资料中没有的专业知识、常识推断或主观判断
            3. **禁止编造**：如果参考资料不包含某条信息，直接说"文档未提及"，不要猜测或推理补充
            4. **逐条标注出处**：对于复杂问题，每个关键结论后标注引用片段（用【】括起来）

            ## 回答格式
            - 优先使用分点列举，保持简洁
            - 涉及多模块对比时，按模块分别列出
            - 涉及推理分析时，先列原文事实，再列基于原文的推理（标注"基于原文推理"）
            - 当资料信息不足以完整回答时，明确说明"以下仅基于文档已有信息"

            ## 特殊情况处理
            - 资料中完全没有相关信息 → 回复"根据提供的文档，没有找到相关信息"
            - 资料部分覆盖问题 → 先回答有依据的部分，再说明"文档未提及xxx方面"
            """;

    public ChatService(OpenAiChatModel chatModel,
                       EmbeddingStore<TextSegment> embeddingStore,
                       EmbeddingModel embeddingModel,
                       ChatHistoryMapper chatHistoryMapper,
                       ChatMemoryStore chatMemoryStore,
                       RAGSearchService ragSearchService,
                       QueryRewriteService queryRewriteService,
                       TokenCountEstimator tokenCountEstimator,
                       ThreadPoolTaskExecutor taskExecutor) {
        this.chatModel = chatModel;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatHistoryMapper = chatHistoryMapper;
        this.chatMemoryStore = chatMemoryStore;
        this.ragSearchService = ragSearchService;
        this.queryRewriteService = queryRewriteService;
        this.tokenCountEstimator = tokenCountEstimator;
        this.taskExecutor = taskExecutor;
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
     * @return 包含 AI 回答、来源引用和会话 ID 的响应对象
     */
    @Timed("RAG 全流程")
    public HaiChatResponse chat(String sessionId, String question) {
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
                .strategy(RewriteStrategyEnum.SIMPLE_REWRITE)//默认策略为 SIMPLE_REWRITE
                .build();
        QueryRewriteResult rewriteResult = queryRewriteService.rewrite(rewriteRequest);

        String searchQuery = rewriteResult.getRewrittenQuery();//意图词
        List<String> expandKeywords = rewriteResult.getExpandKeywords();//扩展关键词
        List<String> excludeKeywords = rewriteResult.getExcludeKeywords();//排除关键词
        String hypotheticAnswer = rewriteResult.getHypotheticAnswer(); //假设性答案
        List<String> subQueries = rewriteResult.getSubQueries(); //任务分解子查询
        log.info("查询改写 | 原始: {} | 改写: {} | 扩展词: {} | 排除词: {} | 子查询: {} | 置信度: {} | 路径: {} | 策略: {}",
                question, searchQuery, expandKeywords, excludeKeywords,
                subQueries != null ? subQueries.size() : 0,
                String.format("%.2f", rewriteResult.getConfidence()), rewriteResult.getPath(),
                rewriteResult.getStrategy());

        // 步骤1：混合检索（支持多子查询分别检索 + 合并去重）
        List<HybridSearchService.RankedResult> rankedResults;
        if (subQueries != null && !subQueries.isEmpty()) {// 多子查询检索：分别对每个子查询进行检索，合并结果并重新排序
            rankedResults = multiQueryRetrieve(subQueries, topK, expandKeywords, excludeKeywords);//多子查询检索
                       log.info("多子查询检索: {} 个子查询, 合并后 {} 个片段", subQueries.size(), rankedResults.size());
        } else {
            rankedResults = ragSearchService.retrieveSegments(searchQuery, topK, expandKeywords, excludeKeywords);
            log.info("检索到 {} 个相关文档片段", rankedResults.size());
        }
        List<TextSegment> segments = rankedResults.stream()
                .map(HybridSearchService.RankedResult::getSegment)
                .collect(Collectors.toList());

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
        String answer = callLLM(allMessages);

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

    /**
     * 删除历史会话及其消息
     *
     * @param userId 指定用户ID则只删除该用户的记录，为 null 则删除全部
     */
    public void deleteAllSessions(String userId) {
        if (userId == null || userId.isBlank()) {
            // 删除全部
            chatHistoryMapper.delete(new LambdaQueryWrapper<>());
            chatMemoryCache.invalidateAll();
            log.info("已删除所有历史会话");
        } else {
            // 按用户删除：先查该用户的所有 sessionId，再逐条清理
            LambdaQueryWrapper<ChatHistory> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.select(ChatHistory::getSessionId)
                    .eq(ChatHistory::getUserId, userId)
                    .groupBy(ChatHistory::getSessionId);
            List<String> sessionIds = chatHistoryMapper.selectList(queryWrapper).stream()
                    .map(ChatHistory::getSessionId)
                    .distinct()
                    .collect(Collectors.toList());

            // 删除数据库记录
            LambdaQueryWrapper<ChatHistory> deleteWrapper = new LambdaQueryWrapper<>();
            deleteWrapper.eq(ChatHistory::getUserId, userId);
            chatHistoryMapper.delete(deleteWrapper);

            // 清除缓存
            for (String sessionId : sessionIds) {
                chatMemoryCache.invalidate(sessionId);
                chatMemoryStore.deleteMessages(sessionId);
            }

            log.info("已删除用户 {} 的历史会话，共 {} 个会话", userId, sessionIds.size());
        }
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

    /**
     * LLM 调用包装（手动计时，Spring AOP 不支持 private 方法拦截）
     */
    private String callLLM(List<ChatMessage> messages) {
        long start = System.currentTimeMillis();
        log.info("LLM 调用 | 发送内容: {}", messages);
        ChatResponse response = chatModel.chat(messages);

        log.info("[Timed] LLM 生成 | 耗时: {}ms", System.currentTimeMillis() - start);
        return response.aiMessage().text();
    }

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
     * "三明治"布局重排：最相关片段放首尾，一般片段放中间
     * <p>输入已按分数降序排列，输出为 [0, 2, 4, ..., 5, 3, 1] 的交叉排列：
     * 偶数索引（0,2,4...）放前面，奇数索引倒序（...,3,1）放后面，
     * 确保最相关的两个片段占据首尾高关注位置，缓解 LLM "lost in the middle" 问题。</p>
     */
    private List<String> sandwichOrder(List<TextSegment> segments) {
        List<String> texts = segments.stream().map(TextSegment::text).collect(Collectors.toList());
        if (texts.size() <= 2) return texts;

        List<String> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += 2) {
            result.add(texts.get(i));
        }
        int start = texts.size() % 2 == 0 ? texts.size() - 1 : texts.size() - 2;
        for (int i = start; i >= 1; i -= 2) {
            result.add(texts.get(i));
        }
        return result;
    }

    /**
     * 多子查询检索：并行执行各子查询的混合检索，合并去重后按分数降序排列
     *
     * <p>每个子查询分配 topK/subQueries.size() 个结果配额，
     * 通过文本内容去重（避免同一片段被重复计入），
     * 并行执行以降低多子查询场景的总延迟，最终按分数降序排列返回。</p>
     */
    private List<HybridSearchService.RankedResult> multiQueryRetrieve(
            List<String> subQueries, int totalTopK,
            List<String> expandKeywords, List<String> excludeKeywords) {
        int perQueryTopK = Math.max(3, totalTopK / subQueries.size());

        List<CompletableFuture<List<HybridSearchService.RankedResult>>> futures = new ArrayList<>();
        for (String subQuery : subQueries) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> ragSearchService.retrieveSegments(subQuery, perQueryTopK, expandKeywords, excludeKeywords),
                    taskExecutor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Set<String> seenTexts = new LinkedHashSet<>();
        List<HybridSearchService.RankedResult> merged = new ArrayList<>();
        for (CompletableFuture<List<HybridSearchService.RankedResult>> future : futures) {
            try {
                List<HybridSearchService.RankedResult> results = future.join();
                for (HybridSearchService.RankedResult r : results) {
                    if (seenTexts.add(r.getSegment().text())) {
                        merged.add(r);
                    }
                }
            } catch (Exception e) {
                log.warn("子查询检索失败，跳过: {}", e.getMessage());
            }
        }

        merged.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        log.debug("多子查询检索合并: {} 路并行, 去重后={}",
                subQueries.size(), merged.size());
        return merged;
    }

    private ContextBuildResult buildContextWithinBudget(List<TextSegment> segments, int maxTokens) {
        if (segments == null || segments.isEmpty()) {
            return new ContextBuildResult("", 0, 0);
        }

        StringBuilder contextBuilder = new StringBuilder();
        int usedTokens = 0;
        int usedSegments = 0;
        String separator = "\n\n---\n\n";
        int separatorTokens = tokenCountEstimator.estimateTokenCountInText(separator);

        List<String> ordered = sandwichOrder(segments);
        for (int i = 0; i < ordered.size(); i++) {
            String text = ordered.get(i);
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

        // 0. 快速检查整体是否满足
        int totalTokens = tokenCountEstimator.estimateTokenCountInText(text);
        if (totalTokens <= maxTokens) {
            return text;
        }

        // 1. 尝试按段落截断（优先）
        List<Integer> paragraphBoundaries = findParagraphBoundaries(text);
        if (!paragraphBoundaries.isEmpty()) {
            String result = truncateAtBoundaries(text, paragraphBoundaries, maxTokens);
            if (result != null) {
                return result; // 成功按段落截断
            }
            // 如果段落边界全部超出预算，继续尝试句子级
        }

        // 2. 尝试按句子截断（次优）
        List<Integer> sentenceBoundaries = findSentenceBoundaries(text);
        if (!sentenceBoundaries.isEmpty()) {
            String result = truncateAtBoundaries(text, sentenceBoundaries, maxTokens);
            if (result != null) {
                return result; // 成功按句子截断
            }
            // 如果句子边界也全部超出，回退到字符截断
        }

        // 3. 最终回退：按字符二分截断
        return binarySearchTruncate(text, maxTokens);
    }

    /**
     * 寻找段落边界（连续两个换行符的结束位置）
     * 支持 \n\n 和 \r\n\r\n
     */
    private List<Integer> findParagraphBoundaries(String text) {
        List<Integer> boundaries = new ArrayList<>();
        int len = text.length();
        int i = 0;
        while (i < len) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                // 检查是否有连续两个换行符
                if (i + 1 < len) {
                    char next = text.charAt(i + 1);
                    if ((c == '\n' && next == '\n') || (c == '\r' && next == '\n' && i + 2 < len && text.charAt(i + 2) == '\n')) {
                        // 找到段落结束位置（即第二个换行符的索引）
                        int endIdx;
                        if (c == '\r') {
                            // \r\n\r\n 模式，结束在第三个 \n 的索引? 实际是两个换行符，我们取第二个 \n 的位置
                            endIdx = i + 3; // \r\n\r\n 的最后一个字符索引
                        } else {
                            endIdx = i + 1; // \n\n 的第二个 \n
                        }
                        boundaries.add(endIdx);
                        // 跳过这段，避免重复
                        i = endIdx + 1;
                        continue;
                    }
                }
            }
            i++;
        }
        return boundaries;
    }

    /**
     * 寻找句子边界（中英文标点 + 换行符）
     */
    private List<Integer> findSentenceBoundaries(String text) {
        List<Integer> boundaries = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '？' || c == '！' || c == '.' || c == '?' || c == '!'
                    || c == '\n' || c == '；' || c == ';') {
                boundaries.add(i);
            }
        }
        return boundaries;
    }

    /**
     * 在候选边界列表中，用二分查找找 ≤ maxTokens 的最大边界，返回截断后的子串
     * 如果找不到任何边界 ≤ maxTokens，返回 null
     */
    private String truncateAtBoundaries(String text, List<Integer> boundaries, int maxTokens) {
        int low = 0, high = boundaries.size() - 1;
        int bestIdx = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int pos = boundaries.get(mid);
            // 截断到 pos+1（包含标点/换行符）
            String candidate = text.substring(0, pos + 1);
            int tokenCount = tokenCountEstimator.estimateTokenCountInText(candidate);
            if (tokenCount <= maxTokens) {
                bestIdx = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (bestIdx != -1) {
            return text.substring(0, boundaries.get(bestIdx) + 1);
        }
        return null; // 没有合适的边界
    }

    /**
     * 最终回退：按字符二分截断（保证不切断字符）
     */
    private String binarySearchTruncate(String text, int maxTokens) {
        int low = 0, high = text.length();
        int bestLen = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            String sub = text.substring(0, mid);
            int tokenCount = tokenCountEstimator.estimateTokenCountInText(sub);
            if (tokenCount <= maxTokens) {
                bestLen = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, bestLen);
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