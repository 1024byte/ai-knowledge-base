package com.hai.aiknowledgebase.queryrewrite;

import com.hai.aiknowledgebase.annotation.Timed;
import com.hai.aiknowledgebase.config.ThresholdsConfig;
import com.hai.aiknowledgebase.dto.*;
import com.hai.aiknowledgebase.service.QueryRewriteConfigLoader;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 查询改写服务（Query Rewrite Service）
 *
 * <h2>功能概述</h2>
 * 对用户输入的查询进行改写，输出改写后的查询文本、扩展关键词和排除关键词，
 * 供下游向量检索和 BM25 精确匹配使用。
 *
 * <h2>执行流程</h2>
 * <pre>
 * 输入查询
 *   ├─ 0. 全局开关检查（enabled = false 则直接返回原始查询）
 *   ├─ 1. 空值检查
 *   ├─ 2. 查询纠错（编辑距离匹配词典，纠正错别字）
 *   ├─ 3. 指代消解（LLM 根据对话历史解析"它/这个"等指代词）
 *   ├─ 4. 本地 LLM 改写（优先路径，语义补全与同义词扩展）
 *   │   └─ 不可用时降级
 *   └─ 5. L1 规则改写（固定映射 + 同义词替换，降级路径）
 * </pre>
 *
 * <h2>依赖组件</h2>
 * <ul>
 *   <li>{@link QueryRewriteConfigLoader}：词典配置加载器（同义词、固定映射、停用词）</li>
 *   <li>{@link QueryCorrector}：查询纠错服务</li>
 *   <li>{@link QueryRouter}：按需改写路由分类器</li>
 *   <li>{@link LocalLLMRewriter}：本地 LLM 改写器（可选注入）</li>
 *   <li>{@link L1RuleBasedTransformer}：L1 规则改写转换器</li>
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

    /** 查询纠错服务：基于编辑距离的拼写纠错 */
    private final QueryCorrector queryCorrector;

    /** 按需改写路由分类器：根据查询特征选择改写策略 */
    private final QueryRouter queryRouter;

    /** LLM 聊天模型：用于指代消解（DeepSeek via OpenAI 兼容接口） */
    private final OpenAiChatModel chatModel;

    // ==================== 配置属性 ====================

    private final ThresholdsConfig thresholdsConfig;

    /** 全局改写开关，默认 true。设为 false 则所有查询直接返回原始文本 */
    @Value("${query-rewrite.enabled:true}")
    private boolean enabled;

    @Autowired(required = false)
    private LocalLLMRewriter localLLMRewriter;

    private final L1RuleBasedTransformer l1RuleBasedTransformer;


    // ==================== 主入口 ====================
    @Timed("查询改写")
    public QueryRewriteResult rewrite(RewriteRequest request){

        // Step 0: 全局开关检查
        if (!enabled) {
            log.info("全局改写开关已关闭，直接返回原始查询: {}", request.getQuery());
            return buildNoneResult(request.getQuery());
        }

        String query = request.getQuery();
        RewriteStrategyEnum strategy = request.getStrategy();//获取改写策略
        List<CustomChatMessage> history = request.getTruncatedHistory();
        // Step 1: 空值检查
        if (query == null || query.isBlank()) {
            return QueryRewriteResult.builder()
                    .rewrittenQuery("")
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.0)
                    .path(RewritePath.L1_RULE)
                    .build();
        }
        String currentQuery = query.trim();
        // Step 1: 纠错（保留 L1+L2 漏斗纠错，移除 L3 LLM 纠错避免重复调用）
        currentQuery = queryCorrector.correct(currentQuery);
        log.debug("纠错后: {} → {}", query, currentQuery);

        // Step 2: 指代消解（保留，需要对话历史）
        if (history != null && !history.isEmpty() && needsCoreferenceResolution(currentQuery)) {
            String resolved = resolveCoreference(currentQuery, history);
            if (resolved != null && !resolved.isBlank()) {
                currentQuery = resolved;
                log.debug("指代消解后: {}", currentQuery);
            }
        }

        // Step 3: 本地 LLM 改写（替代 L1 规则 + L2 NLP）
        if (localLLMRewriter != null && localLLMRewriter.isAvailable()) {
            List<String> historyTexts = extractHistoryTexts(history);
            QueryRewriteResult llmResult = localLLMRewriter.rewrite(currentQuery, historyTexts);
            return enrichWithStrategy(llmResult, strategy);
        }

        // Step 4: 降级 — 本地 LLM 不可用时，走 L1 固定映射（仅保留 fixed-mapping，不做同义词扩展）
        log.info("本地 LLM 不可用，降级到 L1 固定映射");
        QueryRewriteResult l1Result = l1RuleBasedTransformer.applyRuleRewrite(currentQuery);
        return enrichWithStrategy(l1Result, strategy);
    }

    /**
     * 根据策略对改写结果进行后置处理
     *
     * <p>DIRECT 策略直接返回原始查询，其他策略保留改写结果并标记策略。</p>
     */
    private QueryRewriteResult enrichWithStrategy(QueryRewriteResult result, RewriteStrategyEnum strategy) {
        if (strategy == RewriteStrategyEnum.DIRECT) {
            return QueryRewriteResult.builder()
                    .rewrittenQuery(result.getRewrittenQuery())
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.0)
                    .path(RewritePath.NONE)
                    .strategy(strategy)
                    .build();
        }
        return QueryRewriteResult.builder()
                .rewrittenQuery(result.getRewrittenQuery())
                .subQueries(result.getSubQueries())
                .expandKeywords(result.getExpandKeywords())
                .excludeKeywords(result.getExcludeKeywords())
                .confidence(result.getConfidence())
                .path(result.getPath())
                .strategy(strategy)
                .build();
    }

    /**
     * 判断查询是否需要指代消解
     * <p>使用正则快速检测查询中是否包含中文指代词，
     * 避免对无指代词的查询发起不必要的 LLM 调用。</p>
     */
    private boolean needsCoreferenceResolution(String query) {
        return PRONOUN_PATTERN.matcher(query).find();
    }

    /**
     * 指代消解：调用 LLM 根据对话历史将指代词替换为具体实体
     * <p>前置条件：调用方已通过 needsCoreferenceResolution 确认查询包含指代词。</p>
     *
     * @param query   包含指代词的查询
     * @param history 对话历史
     * @return 消解后的查询，失败时返回原始查询
     */
    private String resolveCoreference(String query, List<CustomChatMessage> history) {
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
     * 将对话历史转换为文本列表，供本地 LLM 改写器使用
     * <p>每条消息格式化为"角色：内容"，如"用户：什么是EmbeddingStore"</p>
     *
     * @param history 对话历史（可为 null 或空）
     * @return 格式化后的文本列表，空历史返回空列表
     */
    private List<String> extractHistoryTexts(List<CustomChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }
        return history.stream()
                .map(msg -> {
                    String roleLabel = "assistant".equalsIgnoreCase(msg.getRole()) ? "助手" : "用户";
                    return roleLabel + "：" + msg.getContent();
                })
                .collect(Collectors.toList());
    }

    // ==================== 指代消解（Reference Resolution） ====================

    /** 中文指代词正则：快速检测查询中是否包含指代词，避免不必要的 LLM 调用 */
    private static final Pattern PRONOUN_PATTERN = Pattern.compile(
            "它(?:们)?|这个|那个|上面|前面|这里|那里|这些|那些|该(?:文档|文件|系统|项目|功能|模块|方法|类|接口|服务|配置|组件)?|其(?:中)?|这(?:个|些)?|那(?:个|些)?"
    );

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
     * <p>当改写被禁用或查询为空时调用。
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

}