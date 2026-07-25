package com.hai.aiknowledgebase.queryrewrite;

import com.hai.aiknowledgebase.dto.*;
import com.hai.aiknowledgebase.interfaces.LocalQueryRewriter;
import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import com.hai.aiknowledgebase.service.IntentRecognitionOrchestrator;
import com.hai.aiknowledgebase.service.QueryRewriteConfigLoader;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 查询改写服务（Query Rewrite Service）
 *
 * <h2>功能概述</h2>
 * 对用户输入的查询进行多层级改写，输出改写后的查询文本、扩展关键词和排除关键词，
 * 供下游向量检索和 BM25 精确匹配使用。
 *
 * <h2>三层改写架构</h2>
 * <table>
 *   <tr><th>层级</th><th>名称</th><th>技术</th><th>置信度阈值</th></tr>
 *   <tr><td>L1</td><td>规则改写</td><td>固定映射 + 同义词字典 + 区间占用检测</td><td>≥ 0.85</td></tr>
 *   <tr><td>L2</td><td>NLP 增强</td><td>分词 + 纠错 + 意图识别 + 同义词扩展</td><td>≥ 0.70</td></tr>
 *   <tr><td>L3</td><td>LLM 改写</td><td>大模型语义补全、任务分解（可选）</td><td>—</td></tr>
 * </table>
 *
 * <h2>执行流程</h2>
     * <pre>
     * 输入查询
     *   ├─ 0. 查询纠错（编辑距离匹配词典，纠正错别字）
     *   ├─ 1. 指代消解（LLM 根据对话历史解析"它/这个"等指代词）
     *   ├─ 2. L1 规则改写（固定映射 + 同义词替换）
     *   │   └─ 置信度 ≥ 0.85 → 直接返回
     *   ├─ 3. L2 NLP 增强（分词 → 意图识别 → 策略选择 → 置信度计算）
     *   │   ├─ RULE_ONLY 模式 → 直接返回
     *   │   └─ 置信度 ≥ 0.70 → 返回
     *   └─ 4. L3 LLM 改写（仅 FULL 模式，且 L2 不满足阈值时）
     *       ├─ 成功 → 返回 L3 结果
     *       └─ 失败/不可用 → 降级返回 L2 结果
     * </pre>
 *
 * <h2>路由决策</h2>
 * 通过 {@link RewriteRequest#getRoutingDecision()} 控制改写层级：
 * <ul>
 *   <li>{@link RoutingDecision#SKIP}：跳过所有改写，直接返回原始查询</li>
 *   <li>{@link RoutingDecision#RULE_ONLY}：仅执行 L1 + L2，不调用 L3</li>
 *   <li>{@link RoutingDecision#FULL}：执行完整三层 pipeline</li>
 * </ul>
 *
 * <h2>依赖组件</h2>
 * <ul>
 *   <li>{@link QueryRewriteConfigLoader}：词典配置加载器（同义词、固定映射、停用词）</li>
 *   <li>{@link ChineseTokenizerService}：中文分词服务</li>
 *   <li>{@link IntentRecognitionOrchestrator}：意图识别编排器（规则引擎 + LLM 策略链）</li>
 *   <li>{@link QueryCorrector}：查询纠错服务</li>
 *   <li>{@link LocalQueryRewriter}：L3 LLM 改写器（可选注入）</li>
 * </ul>
 *
 * @see QueryRewriteResult 改写结果 DTO
 * @see RewriteRequest 改写请求 DTO
 * @see QueryRewriteConfigLoader 词典配置
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService{

    // ==================== 依赖注入 ====================

    /** 词典配置加载器：提供同义词词典、固定映射、停用词，支持定时热加载 */
    private final QueryRewriteConfigLoader configLoader;

    /** 中文分词服务：用于查询分词和关键词提取 */
    private final ChineseTokenizerService tokenizerService;

    /** 意图识别编排器：策略链（规则引擎 → LLM），输出意图 + 置信度 + 改写提示 */
    private final IntentRecognitionOrchestrator orchestrator;

    /** 查询纠错服务：基于编辑距离的拼写纠错 */
    private final QueryCorrector queryCorrector;

    /** 按需改写路由分类器：根据查询特征选择改写策略 */
    private final QueryRouter queryRouter;

    /** LLM 聊天模型：用于指代消解（DeepSeek via OpenAI 兼容接口） */
    private final OpenAiChatModel chatModel;

    // ==================== 配置属性 ====================

    /** L1 规则改写置信度阈值，默认 0.85。L1 结果达到此阈值则跳过 L2/L3 */
    @Value("${query-rewrite.l1.confidence-threshold:0.85}")
    private double l1ConfidenceThreshold;

    /** L2 NLP 改写置信度阈值，默认 0.70。L2 结果达到此阈值则跳过 L3 */
    @Value("${query-rewrite.l2.confidence-threshold:0.70}")
    private double l2ConfidenceThreshold;

    /** 全局改写开关，默认 true。设为 false 则所有查询直接返回原始文本 */
    @Value("${query-rewrite.enabled:true}")
    private boolean enabled;

    /** L3 LLM 改写器（可选注入）。如果未配置 Bean 则为 null，L3 不可用 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LocalQueryRewriter llmRewriter;


    private final L1RuleBasedTransformer l1RuleBasedTransformer;

    private final L2NLPBasedTransformer l2NLPBasedTransformer;

    // ==================== 主入口 ====================

    /**
     * 简化版入口：执行默认 SIMPLE_REWRITE 路由的查询改写
     *
     * <p>该方法内部构造 {@link RewriteRequest} 并设置 strategy 为
     * {@link RewriteStrategyEnum#SIMPLE_REWRITE}，适用于无对话历史的单次查询。
     * 如需按需路由，请使用 {@link #rewrite(RewriteRequest)}。</p>
     *
     * @param query 原始用户查询文本
     * @return 改写结果，包含改写后查询、扩展关键词、排除关键词、置信度和改写路径
     */
    public QueryRewriteResult rewrite(String query) {
        RewriteRequest request = RewriteRequest.builder()
                .query(query)
                .strategy(RewriteStrategyEnum.SIMPLE_REWRITE)
                .build();
        return rewrite(request);
    }

    /**
     * 完整版入口：根据路由决策执行按需多层查询改写
     *
     * <h3>按需路由流程</h3>
     * <ol>
     *   <li><b>前置检查</b>：改写开关、空值</li>
     *   <li><b>路由决策</b>：优先使用 request.strategy，否则通过 QueryRouter 自动判定</li>
     *   <li><b>按策略执行</b>：
     *       <ul>
     *         <li>DIRECT → 直接返回原始查询</li>
     *         <li>CORRECT_ONLY → 仅纠错</li>
     *         <li>RESOLVE_ONLY → 纠错 + 指代消解</li>
     *         <li>SIMPLE_REWRITE → 纠错 + 消解 + L1 + L2</li>
     *         <li>DECOMPOSE → 纠错 + 消解 + 问题分解</li>
     *         <li>HYDE → 生成假设性答案（检索后触发，不在此处）</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @param request 改写请求，包含查询文本、对话历史、策略和会话 ID
     * @return 改写结果
     */
    public QueryRewriteResult rewrite(RewriteRequest request) {
        if (!enabled) {
            log.debug("查询改写已禁用，直接返回原始查询");
            return buildNoneResult(request.getQuery());
        }

        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            return buildNoneResult(query);
        }

        String trimmed = query.trim();
        long startTime = System.currentTimeMillis();

        RewriteStrategyEnum strategy = resolveStrategy(request, trimmed);

        switch (strategy) {
            case DIRECT:
                log.debug("策略 DIRECT: 直接返回原始查询");
                QueryRewriteResult directResult = buildDirectResult(trimmed);
                logRewriteResult("DIRECT", trimmed, directResult, startTime);
                return directResult;

            case CORRECT_ONLY:
                log.debug("策略 CORRECT_ONLY: 仅纠错");
                return executeCorrectOnly(trimmed, startTime);

            case RESOLVE_ONLY:
                log.debug("策略 RESOLVE_ONLY: 纠错 + 消解");
                return executeResolveOnly(trimmed, request.getTruncatedHistory(), startTime);

            case SIMPLE_REWRITE:
                log.debug("策略 SIMPLE_REWRITE: 纠错 + 消解 + L1/L2 改写");
                return executeSimpleRewrite(trimmed, request, startTime);

            case DECOMPOSE:
                log.debug("策略 DECOMPOSE: 纠错 + 消解 + 问题分解");
                return executeDecompose(trimmed, request.getTruncatedHistory(), startTime);

            case HYDE:
                log.debug("策略 HYDE: 触发假设性答案生成");
                return executeHyde(trimmed, request.getTruncatedHistory(), startTime);

            default:
                log.warn("未知策略: {}, 降级为 SIMPLE_REWRITE", strategy);
                return executeSimpleRewrite(trimmed, request, startTime);
        }
    }

    /**
     * 解析改写策略：优先使用 request 中的 strategy，否则通过 QueryRouter 自动判定
     *
     * <p>向后兼容：如果 request 中设置了旧的 routingDecision，自动映射到新策略。</p>
     */
    private RewriteStrategyEnum resolveStrategy(RewriteRequest request, String query) {
        if (request.getStrategy() != null) {
            return request.getStrategy();
        }

        RoutingDecision decision = request.getRoutingDecision();
        if (decision != null) {
            return mapFromRoutingDecision(decision);
        }

        return queryRouter.route(query, request.getTruncatedHistory());
    }

    /**
     * 旧 RoutingDecision → 新 RewriteStrategyEnum 映射
     */
    private RewriteStrategyEnum mapFromRoutingDecision(RoutingDecision decision) {
        switch (decision) {
            case SKIP:
                return RewriteStrategyEnum.DIRECT;
            case RULE_ONLY:
            case FULL:
            default:
                return RewriteStrategyEnum.SIMPLE_REWRITE;
        }
    }

    // ==================== 策略执行方法 ====================

    /**
     * DIRECT 策略：直接返回原始查询，不做任何处理
     */
    private QueryRewriteResult buildDirectResult(String query) {
        return QueryRewriteResult.builder()
                .rewrittenQuery(query)
                .expandKeywords(Collections.emptyList())
                .excludeKeywords(Collections.emptyList())
                .confidence(1.0)
                .path(RewritePath.NONE)
                .strategy(RewriteStrategyEnum.DIRECT)
                .build();
    }

    /**
     * CORRECT_ONLY 策略：仅执行三层漏斗纠错
     */
    private QueryRewriteResult executeCorrectOnly(String query, long startTime) {
        String correctedQuery = queryCorrector.correct(query);
        if (!correctedQuery.equals(query)) {
            log.info("查询纠错 | 原始: {} | 纠错后: {}", query, correctedQuery);
        }
        QueryRewriteResult result = QueryRewriteResult.builder()
                .rewrittenQuery(correctedQuery)
                .expandKeywords(Collections.emptyList())
                .excludeKeywords(Collections.emptyList())
                .confidence(correctedQuery.equals(query) ? 1.0 : 0.9)
                .path(correctedQuery.equals(query) ? RewritePath.NONE : RewritePath.L1_RULE)
                .strategy(RewriteStrategyEnum.CORRECT_ONLY)
                .build();

        logRewriteResult("CORRECT_ONLY", query, result, startTime);
        return result;
    }

    /**
     * RESOLVE_ONLY 策略：纠错 + 指代消解，不改写
     */
    private QueryRewriteResult executeResolveOnly(String query, List<CustomChatMessage> history, long startTime) {
        String correctedQuery = queryCorrector.correct(query);
        if (!correctedQuery.equals(query)) {
            log.info("查询纠错 | 原始: {} | 纠错后: {}", query, correctedQuery);
        }

        String resolvedQuery = resolveReferences(correctedQuery, history);
        if (!resolvedQuery.equals(correctedQuery)) {
            log.info("指代消解 | 原始: {} | 消解后: {}", correctedQuery, resolvedQuery);
        }

        boolean wasRewritten = !resolvedQuery.equals(query);
        QueryRewriteResult result = QueryRewriteResult.builder()
                .rewrittenQuery(resolvedQuery)
                .expandKeywords(Collections.emptyList())
                .excludeKeywords(Collections.emptyList())
                .confidence(wasRewritten ? 0.85 : 1.0)
                .path(wasRewritten ? RewritePath.L1_RULE : RewritePath.NONE)
                .strategy(RewriteStrategyEnum.RESOLVE_ONLY)
                .build();

        logRewriteResult("RESOLVE_ONLY", query, result, startTime);
        return result;
    }

    /**
     * SIMPLE_REWRITE 策略：纠错 + 消解 + L1规则 + L2 NLP增强（原有完整流水线）
     */
    private QueryRewriteResult executeSimpleRewrite(String query, RewriteRequest request, long startTime) {
        List<CustomChatMessage> history = request.getTruncatedHistory();

        String correctedQuery = queryCorrector.correct(query);
        if (!correctedQuery.equals(query)) {
            log.info("查询纠错 | 原始: {} | 纠错后: {}", query, correctedQuery);
        }
        //执行指代消解
        String resolvedQuery = resolveReferences(correctedQuery, history);
        if (!resolvedQuery.equals(correctedQuery)) {
            log.info("指代消解 | 原始: {} | 消解后: {}", correctedQuery, resolvedQuery);
        }

        QueryRewriteResult l1Result = l1RuleBasedTransformer.applyRuleRewrite(resolvedQuery);
        if (l1Result.getConfidence() >= l1ConfidenceThreshold) {
            QueryRewriteResult result = enrichWithStrategy(l1Result, RewriteStrategyEnum.SIMPLE_REWRITE);
            logRewriteResult("L1", resolvedQuery, result, startTime);
            return result;
        }

        QueryRewriteResult l2Result = l2NLPBasedTransformer.applyNlpRewrite(resolvedQuery, l1Result);
        if (l2Result.getConfidence() >= l2ConfidenceThreshold) {
            QueryRewriteResult result = enrichWithStrategy(l2Result, RewriteStrategyEnum.SIMPLE_REWRITE);
            logRewriteResult("L2", resolvedQuery, result, startTime);
            return result;
        }

        if (llmRewriter != null) {
            try {
                RewriteRequest l3Request = RewriteRequest.builder()
                        .query(resolvedQuery)
                        .truncatedHistory(history)
                        .strategy(RewriteStrategyEnum.SIMPLE_REWRITE)
                        .sessionId(request.getSessionId())
                        .build();
                QueryRewriteResult l3Result = llmRewriter.rewrite(l3Request);
                if (l3Result != null && l3Result.isRewritten()) {
                    QueryRewriteResult result = enrichWithStrategy(l3Result, RewriteStrategyEnum.SIMPLE_REWRITE);
                    logRewriteResult("L3", resolvedQuery, result, startTime);
                    return result;
                }
            } catch (Exception e) {
                log.error("L3 LLM 改写失败，降级使用 L2 结果: {}", e.getMessage());
            }
        }

        QueryRewriteResult result = enrichWithStrategy(l2Result, RewriteStrategyEnum.SIMPLE_REWRITE);
        logRewriteResult("L2(L3降级)", resolvedQuery, result, startTime);
        return result;
    }

    /**
     * DECOMPOSE 策略：纠错 + 消解 + 问题分解为多个子查询
     */
    private QueryRewriteResult executeDecompose(String query, List<CustomChatMessage> history, long startTime) {
        String correctedQuery = queryCorrector.correct(query);
        if (!correctedQuery.equals(query)) {
            log.info("查询纠错 | 原始: {} | 纠错后: {}", query, correctedQuery);
        }

        String resolvedQuery = resolveReferences(correctedQuery, history);
        if (!resolvedQuery.equals(correctedQuery)) {
            log.info("指代消解 | 原始: {} | 消解后: {}", correctedQuery, resolvedQuery);
        }

        List<String> subQueries = decomposeQuery(resolvedQuery);
        if (subQueries.isEmpty()) {
            log.warn("问题分解失败，降级为 SIMPLE_REWRITE: {}", resolvedQuery);
            return executeSimpleRewrite(query, RewriteRequest.builder()
                    .query(query).truncatedHistory(history).strategy(RewriteStrategyEnum.SIMPLE_REWRITE).build(), startTime);
        }

        log.info("问题分解: {} → {} 个子查询", resolvedQuery, subQueries.size());

        QueryRewriteResult result = QueryRewriteResult.builder()
                .rewrittenQuery(resolvedQuery)
                .subQueries(subQueries)
                .expandKeywords(Collections.emptyList())
                .excludeKeywords(Collections.emptyList())
                .confidence(0.75)
                .path(RewritePath.L3_TASK_DECOMPOSITION)
                .strategy(RewriteStrategyEnum.DECOMPOSE)
                .build();

        logRewriteResult("DECOMPOSE", query, result, startTime);
        return result;
    }

    /**
     * HYDE 策略：生成假设性答案用于检索
     *
     * <p>通常由检索后质量检查触发，而非在主流程中直接调用。</p>
     */
    private QueryRewriteResult executeHyde(String query, List<CustomChatMessage> history, long startTime) {
        String correctedQuery = queryCorrector.correct(query);
        if (!correctedQuery.equals(query)) {
            log.info("查询纠错 | 原始: {} | 纠错后: {}", query, correctedQuery);
        }

        String resolvedQuery = resolveReferences(correctedQuery, history);
        if (!resolvedQuery.equals(correctedQuery)) {
            log.info("指代消解 | 原始: {} | 消解后: {}", correctedQuery, resolvedQuery);
        }

        String hypotheticAnswer = generateHydeAnswer(resolvedQuery);
        if (hypotheticAnswer == null || hypotheticAnswer.isBlank()) {
            log.warn("HyDE 生成失败，降级返回原始查询: {}", resolvedQuery);
            QueryRewriteResult result = buildDirectResult(resolvedQuery);
            logRewriteResult("HYDE(降级)", query, result, startTime);
            return result;
        }

        QueryRewriteResult result = QueryRewriteResult.builder()
                .rewrittenQuery(resolvedQuery)
                .hypotheticAnswer(hypotheticAnswer)
                .expandKeywords(Collections.emptyList())
                .excludeKeywords(Collections.emptyList())
                .confidence(0.60)
                .path(RewritePath.L3_HYDE)
                .strategy(RewriteStrategyEnum.HYDE)
                .build();

        logRewriteResult("HYDE", query, result, startTime);
        return result;
    }

    /**
     * 问题分解：使用 LLM 将复合查询拆分为多个独立子查询
     */
    private List<String> decomposeQuery(String query) {
        try {
            String prompt = String.format("""
                    将以下复合查询拆分为多个独立的简单查询，每个子查询对应一个检索目标。

                    规则：
                    1. 如果查询包含对比（如"A和B的区别"），拆分为"A"、"B"、"A和B的对比"
                    2. 如果包含多个并列问题，各自独立
                    3. 每个子查询 ≤ 30 字
                    4. 返回 JSON 数组格式：["子查询1", "子查询2"]
                    5. 只返回 JSON 数组，不要任何解释

                    查询：%s
                    子查询 JSON：""", query);

            String response = chatModel.chat(
                    SystemMessage.from("你是一个查询分解助手。只返回 JSON 数组格式的子查询列表。"),
                    UserMessage.from(prompt)
            ).aiMessage().text();

            if (response != null && !response.isBlank()) {
                return parseSubQueries(response.trim());
            }
        } catch (Exception e) {
            log.warn("LLM 问题分解失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 解析 LLM 返回的 JSON 数组为子查询列表
     */
    private List<String> parseSubQueries(String json) {
        String cleaned = json.replaceAll("^\\[|\\]$", "").trim();
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        for (String part : cleaned.split("\",\\s*\"")) {
            String trimmed = part.replaceAll("^\"|\"$", "").trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 生成 HyDE 假设性答案
     *
     * <p>让 LLM 假设自己已经知道答案，生成一段知识片段，
     * 用这段文本进行向量检索，利用语义相似度找到真正相关的文档。</p>
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

    /**
     * 为改写结果设置策略字段
     */
    private QueryRewriteResult enrichWithStrategy(QueryRewriteResult result, RewriteStrategyEnum strategy) {
        result.setStrategy(strategy);
        return result;
    }

    // ==================== 指代消解（Reference Resolution） ====================

    /** 中文指代词正则：快速检测查询中是否包含指代词，避免不必要的 LLM 调用 */
    private static final Pattern PRONOUN_PATTERN = Pattern.compile(
            "它(?:们)?|这个|那个|上面|前面|这里|那里|这些|那些|该(?:文档|文件|系统|项目|功能|模块|方法|类|接口|服务|配置|组件)?|其(?:中)?|这(?:个|些)?|那(?:个|些)?"
    );

    /**
     * 指代消解：使用 LLM 根据对话历史将指代词替换为具体实体
     *
     * <h3>为什么用 LLM 而不是正则？</h3>
     * 正则无法理解语义。比如历史中有 "EmbeddingStore" 和 "LangChain4j" 两个实体，
     * 正则只能"取最长"碰运气，但 LLM 能从上下文推断"它"指的是 "EmbeddingStore"。
     *
     * <h3>策略</h3>
     * <ol>
     *   <li>对话历史为空 → 直接返回（无上下文可参考）</li>
     *   <li>查询中不包含指代词 → 直接返回（无需消解）</li>
     *   <li>包含指代词 → 调用 LLM 根据历史上下文消解</li>
     *   <li>LLM 失败/超时 → 返回原始查询，不阻塞主流程</li>
     * </ol>
     *
     * @param query   原始查询文本
     * @param history 截断后的对话历史（最近 2 轮）
     * @return 消解后的查询文本（如无指代词或无法消解，返回原始查询）
     */
    private String resolveReferences(String query, List<CustomChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return query;
        }

        Matcher pronounMatcher = PRONOUN_PATTERN.matcher(query);
        if (!pronounMatcher.find()) {
            return query;
        }

        try {
            String resolved = resolveReferencesWithLLM(query, history);
            if (resolved != null && !resolved.isBlank() && !resolved.equals(query)) {
                return resolved.trim();
            }
        } catch (Exception e) {
            log.warn("LLM 指代消解失败，保持原始查询: {}", e.getMessage());
        }

        return query;
    }

    /**
     * 调用 LLM 进行指代消解
     *
     * <p>将对话历史拼接为纯文本格式，通过 System Prompt 约束 LLM 只返回消解后的查询，
     * 不附加任何解释。LLM 天然具备语义理解能力，能准确判断指代关系。</p>
     *
     * @param query   包含指代词的查询
     * @param history 对话历史
     * @return 消解后的查询文本
     */
    private String resolveReferencesWithLLM(String query, List<CustomChatMessage> history) {
        StringBuilder historyText = new StringBuilder();
        for (CustomChatMessage msg : history) {
            String roleLabel = "assistant".equalsIgnoreCase(msg.getRole()) ? "助手" : "用户";
            historyText.append(roleLabel).append("：").append(msg.getContent()).append("\n");
        }

        String prompt = String.format("""
                根据对话历史，将用户最新查询中的指代词（如"它"、"这个"、"那个"、"上面"、"该文档"等）替换为具体的事物名称。

                规则：
                1. 如果查询中没有指代词，原样返回。
                2. 如果查询中有指代词，根据对话上下文替换为明确的实体。
                3. 只替换指代词，不要改动查询的其他部分。
                4. 如果无法确定指代目标，原样返回查询。
                5. 只返回消解后的查询文本，不要任何解释、前缀或标点。

                对话历史：
                %s
                最新用户查询：%s
                消解后查询：""", historyText, query);

        return chatModel.chat(
                SystemMessage.from("你是一个指代消解助手。只返回消解后的查询文本，不要任何解释。"),
                UserMessage.from(prompt)
        ).aiMessage().text();
    }


    // ==================== 工具方法 ====================

    /**
     * 构建"无改写"结果
     *
     * <p>当改写被禁用、查询为空或路由决策为 SKIP 时调用。
     * 返回原始查询，置信度为 0.0，路径为 {@link RewritePath#NONE}。</p>
     *
     * @param query 原始查询（可为 null）
     * @return 空改写结果
     */
    private QueryRewriteResult buildNoneResult(String query) {
        String result = (query != null) ? query.trim() : "";
        return QueryRewriteResult.builder()
                .rewrittenQuery(result)
                .expandKeywords(Collections.emptyList())
                .excludeKeywords(Collections.emptyList())
                .confidence(0.0)
                .path(RewritePath.NONE)
                .build();
    }

    /**
     * 记录改写结果日志
     *
     * <p>使用 INFO 级别记录关键信息：层级、原始查询、改写查询、扩展词数、
     * 排除词数、置信度、耗时（毫秒）。</p>
     *
     * @param level        改写层级标识（如 "L1", "L2", "L3", "L2(L3降级)"）
     * @param originalQuery 原始查询文本
     * @param result        改写结果
     * @param startTime     改写开始时间（System.currentTimeMillis()）
     */
    private void logRewriteResult(String level, String originalQuery, QueryRewriteResult result, long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        log.info("查询改写完成 | 层级={} | 原始={} | 改写={} | 扩展词数={} | 排除词数={} | 置信度={} | 耗时={}ms",
                level,
                originalQuery,
                result.getRewrittenQuery(),
                result.getExpandKeywords() != null ? result.getExpandKeywords().size() : 0,
                result.getExcludeKeywords() != null ? result.getExcludeKeywords().size() : 0,
                String.format("%.2f", result.getConfidence()),
                duration);
    }

    /**
     * 检查 L3 LLM 改写器是否可用
     *
     * @return true 表示已注入 {@link LocalQueryRewriter} Bean，L3 路径可用
     */
    public boolean hasL3Rewriter() {
        return llmRewriter != null;
    }
}