package com.hai.aiknowledgebase.queryrewrite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hai.aiknowledgebase.config.ThresholdsConfig;
import com.hai.aiknowledgebase.dto.QueryRewriteResult;
import com.hai.aiknowledgebase.dto.RewritePath;
import com.hai.aiknowledgebase.service.ParamValueProvider;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import com.hai.aiknowledgebase.model.ChatModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 本地轻量 LLM 查询改写器
 *
 * <h2>职责</h2>
 * 使用本地 Ollama + Qwen2.5 小模型对用户查询进行改写，
 * 替代 L1 规则引擎（同义词扩展）+ L2 NLP 改写（意图识别+关键词扩展）。
 *
 * <h2>改写 Prompt 策略</h2>
 * <ul>
 *   <li>展开缩写和简称</li>
 *   <li>补充隐含上下文</li>
 *   <li>拆分复合问题</li>
 *   <li>保留核心语义</li>
 * </ul>
 *
 * <h2>保真度校验</h2>
 * 改写结果与原 query 的 embedding cosine similarity > threshold 时才采纳，
 * 否则回退到原 query，防止改写偏离原始意图。
 */
@Slf4j
@Service
public class LocalLLMRewriter {

    public static final String REWRITE_SYSTEM_PROMPT = """
            你是一个严谨的查询改写助手。你的任务是将用户查询改写为适合检索的短句。
            绝对禁止回答问题！绝对禁止生成解释！绝对禁止输出 markdown 格式！
            只允许输出改写后的文本，不要包含任何多余字符。

            严格遵守以下三条铁律：
            1. 【防吞词】：必须原封不动地保留原句中的所有名词（特别是并列的人或物）。例如原句是“A、B或者C”，改写后必须包含A、B、C，严禁只保留A。
            2. 【禁拆分】：遇到用“和、与、或者”连接多个事物的单句（如“A和B的区别”除外），绝对不准拆分子查询，原样改写输出即可。
            3. 【只拆对比】：只有当用户明确问“对比、区别、不同”时，才允许用 "|" 拆分。例如：“A和B的区别” → “A的特点 | B的特点 | A和B的区别对比”。

            其他要求：
            - 不要超过 3 个子查询。
            - 保持原意，展开已知的常见缩写。
            - 【不截断】：改写结果必须完整覆盖原句信息，禁止缩短或截断题干，禁止丢掉"以下哪些/什么/如何"等问句成分。
            """;

    private final ChatModelProvider chatModelProvider;

    private final EmbeddingModel embeddingModel;
    private final ThresholdsConfig thresholdsConfig;
    /** 参数值读取器：改写开关 / 超时 / 保真度阈值支持参数管理热更 */
    private final ParamValueProvider paramValueProvider;

    @Value("${query-rewrite.local-llm.rewrite-timeout-ms:10000}")
    private long rewriteTimeoutMs;

    @Value("${query-rewrite.local-llm.enabled:false}")
    private boolean enabled;

    /** 改写缓存：query → 改写结果 */
    private final Cache<String, QueryRewriteResult> cache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /** 查询改写专用线程池，用于 LLM 调用的超时控制 */
    private final ThreadPoolTaskExecutor rewriteTaskExecutor;

    public LocalLLMRewriter(EmbeddingModel embeddingModel,
                               @Qualifier("rewriteTaskExecutor") ThreadPoolTaskExecutor rewriteTaskExecutor,
                               ThresholdsConfig thresholdsConfig,
                               ParamValueProvider paramValueProvider,
                               ChatModelProvider chatModelProvider) {
        this.embeddingModel = embeddingModel;
        this.rewriteTaskExecutor = rewriteTaskExecutor;
        this.thresholdsConfig = thresholdsConfig;
        this.paramValueProvider = paramValueProvider;
        this.chatModelProvider = chatModelProvider;
    }

    /**
     * 判断是否配置了改写模型并可执行改写。
     *
     * <p>只校验配置与开关，不探测模型服务是否可达；调用失败会在 rewrite 中回退到原查询。</p>
     */
    public boolean isAvailable() {
        return chatModelProvider.isRewriteConfigured()
                && paramValueProvider.getBoolean("queryRewrite.llm.enabled", enabled);
    }

    /**
     * 执行查询改写
     *
     * @param query    原始查询
     * @param history  对话历史（可选，用于指代消解上下文）
     * @return 改写结果
     */
    public QueryRewriteResult rewrite(String query, List<String> history) {
        return rewrite(query, history, false);
    }

    /**
     * 执行查询改写
     *
     * @param query       原始查询
     * @param history     对话历史（可选，用于指代消解上下文）
     * @param bypassCache 是否绕过改写缓存：召回测试调参时需跳过缓存，
     *                    否则同一 query 在缓存有效期内不会重新计算保真度
     * @return 改写结果
     */
    public QueryRewriteResult rewrite(String query, List<String> history, boolean bypassCache) {
        if (!isAvailable()) {
            return fallback(query);
        }

        // 1. 检查缓存（调参场景可绕过）
        if (!bypassCache) {
            QueryRewriteResult cached = cache.getIfPresent(query);
            if (cached != null) {
                log.debug("改写缓存命中: {} → {}", query, cached.getRewrittenQuery());
                return cached;
            }
        }

        // 2. 完整问句直接使用原查询：小模型改写这类查询时容易截断题干，收益为负
        if (isSelfContainedQuestion(query)) {
            log.info("查询已是完整问句，跳过 LLM 改写: {}", query);
            QueryRewriteResult direct = QueryRewriteResult.builder()
                    .rewrittenQuery(query)
                    .subQueries(Collections.emptyList())
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList())
                    .confidence(1.0)
                    .path(RewritePath.NONE)
                    .build();
            cache.put(query, direct);
            return direct;
        }

        // 3. 构建改写 Prompt
        String prompt = buildPrompt(query, history);

        // 4. 调用本地 LLM（带超时）
        String rewritten;
        try {
            rewritten = callWithTimeout(prompt);
        } catch (Exception e) {
            log.warn("本地 LLM 改写失败/超时，回退到原查询: {}", e.getMessage());
            return fallback(query);
        }

        // 5. 后处理 + 子查询提取
        SubQueryParseResult parseResult = parseSubQueries(rewritten);
        rewritten = postProcess(parseResult.mainQuery, query);

        // 5.5 回答式输出检测：小模型可能无视指令直接回答问题，而非改写
        if (isAnswerInsteadOfRewrite(rewritten, query)) {
            log.warn("本地 LLM 未按要求改写(疑似直接回答)，回退到原查询: {} → {}",
                    query, rewritten.substring(0, Math.min(80, rewritten.length())));
            return fallback(query);
        }

        // 6. 保真度校验
        // 只校验主查询：若把子查询拼进来计算，主查询被截断时会被子查询补齐，掩盖截断问题
        double fidelity = computeFidelity(query, rewritten);
        // 保真度阈值：参数管理热更优先，yml（ThresholdsConfig）兜底
        double fidelityThreshold = paramValueProvider.getDouble(
                "queryRewrite.fidelityMinScore", thresholdsConfig.getQueryRewrite().getFidelity());
        if (fidelity < fidelityThreshold) {
            log.info("改写保真度不足 ({} < {})，回退到原查询: {} → {}",
                    String.format("%.3f", fidelity), fidelityThreshold, query, rewritten);
            return fallback(query);
        }

        // 6.5 子查询语义多样性校验：丢弃过度拆分
        List<String> finalSubQueries = parseResult.subQueries;
        if (!finalSubQueries.isEmpty() && !validateSubQueryDiversity(finalSubQueries)) {
            finalSubQueries = Collections.emptyList();
        }

        // 6. 构建结果
        QueryRewriteResult result = QueryRewriteResult.builder()
                .rewrittenQuery(rewritten)
                .subQueries(finalSubQueries)
                .expandKeywords(extractExpandKeywords(rewritten))
                .excludeKeywords(Collections.emptyList())
                .confidence(fidelity)
                .path(RewritePath.LLM_REWRITE)
                .build();

        cache.put(query, result);
        log.info("本地 LLM 改写: {} → {} (子查询: {}, 保真度: {})",
                query, rewritten, finalSubQueries.size(), String.format("%.3f", fidelity));
        return result;
    }

    // ==================== 私有方法 ====================

    /**
     * 判断查询是否已是自洽的完整问句。
     *
     * <p>完整问句（尤其考题题干、带"哪些/如何"的长问句）改写收益低，
     * 小模型反而容易截断题干，因此直接使用原查询。</p>
     */
    private boolean isSelfContainedQuestion(String query) {
        if (query == null || query.length() < 20) {
            return false;
        }
        // 明确要求对比/辨析的查询仍交给 LLM 拆分
        if (query.contains("对比") || query.contains("区别") || query.contains("不同") || query.contains("差异")) {
            return false;
        }
        return query.contains("哪些") || query.contains("什么") || query.contains("为什么")
                || query.contains("如何") || query.contains("怎样") || query.contains("怎么")
                || query.contains("是否") || query.contains("多少") || query.contains("请问");
    }

    private String buildPrompt(String query, List<String> history) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            sb.append("对话历史：\n");
            for (String msg : history) {
                sb.append("- ").append(msg).append("\n");
            }
            sb.append("\n");
        }
        sb.append("用户查询：").append(query).append("\n改写结果：");
        return sb.toString();
    }

    private String callWithTimeout(String prompt) {
        // 超时时间：参数管理热更优先，yml 兜底
        long timeoutMs = paramValueProvider.getInt("queryRewrite.llmTimeoutMs", (int) rewriteTimeoutMs);
        Future<String> future = rewriteTaskExecutor.submit(() ->
                chatModelProvider.rewrite().chat(
                        dev.langchain4j.data.message.SystemMessage.from(REWRITE_SYSTEM_PROMPT),
                        dev.langchain4j.data.message.UserMessage.from(prompt)
                ).aiMessage().text()
        );
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("本地 LLM 改写超时 ({}ms)", timeoutMs);
            throw new RuntimeException("改写超时", e);
        } catch (Exception e) {
            throw new RuntimeException("改写失败", e);
        }
    }

    private String postProcess(String rewritten, String original) {
        // 去除首尾空白和引号
        String result = rewritten.trim()
//                .replaceAll("^[\"'""'']+|[\"'""'']+$", "")
                .replaceAll("^[\"'\u201C\u201D\u2018\u2019]+|[\"'\u201C\u201D\u2018\u2019]+$", "")
                .trim();

        // 如果改写结果为空或与原文完全相同，返回原文
        if (result.isEmpty() || result.equals(original)) {
            return original;
        }

        // 如果改写结果过长（>原文3倍），可能模型发散，截断
        if (result.length() > original.length() * 3) {
            result = result.substring(0, original.length() * 3);
        }

        return result;
    }

    /**
     * 检测 LLM 是否在回答而非改写
     *
     * <p>小模型（如 Qwen2.5 1.5B）可能无视 Prompt 指令，直接生成回答内容。
     * 当改写结果远比原查询长且包含 markdown/答案特征时，判定为"回答式输出"。</p>
     *
     * @param rewritten LLM 输出
     * @param original  原始查询
     * @return true 表示是回答而非改写
     */
    private boolean isAnswerInsteadOfRewrite(String rewritten, String original) {
        if (rewritten == null || rewritten.isEmpty()) {
            return false;
        }
        // 与原文相同则不是回答
        if (rewritten.equals(original)) {
            return false;
        }
        // 指标1：长度远超原文（>1.5倍），说明在生成内容而非改写
        boolean tooLong = rewritten.length() > original.length() * 1.5;
        // 指标2：包含 markdown 格式（**加粗**、`代码`、#标题）
        boolean hasMarkdown = rewritten.contains("**") || rewritten.contains("```")
                || rewritten.startsWith("#");
        // 指标3：包含长句（>50字），改写通常简短
        boolean hasLongSentence = false;
        for (String sentence : rewritten.split("[。；;，,！!？?]")) {
            if (sentence.trim().length() > 50) {
                hasLongSentence = true;
                break;
            }
        }
        return tooLong && (hasMarkdown || hasLongSentence);
    }

    /**
     * 计算改写保真度：原 query 与改写结果的 embedding cosine similarity
     */
    private double computeFidelity(String original, String rewritten) {
        if (original.equals(rewritten)) {
            return 1.0;
        }
        try {
            Embedding emb1 = embeddingModel.embed(original).content();
            Embedding emb2 = embeddingModel.embed(rewritten).content();
            return cosineSimilarity(emb1.vector(), emb2.vector());
        } catch (Exception e) {
            log.warn("保真度计算失败，默认返回 1.0: {}", e.getMessage());
            return 1.0;
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-8);
    }

    /** 子查询硬上限：参数管理值超过该值时仍收敛到该值 */
    private static final int HARD_MAX_SUB_QUERIES = 5;
    /** 子查询默认上限（参数管理 queryRewrite.maxSubQueries 的兜底值） */
    private static final int DEFAULT_MAX_SUB_QUERIES = 3;
    /** 子查询语义多样性阈值：任意两个子查询 embedding cosine > 此值视为重复，需去重 */
    private static final double SUB_QUERY_DIVERSITY_THRESHOLD = 0.85;

    /**
     * 从 LLM 返回的文本中解析子查询（| 分隔）
     *
     * <p>解析后执行三层校验：</p>
     * <ol>
     *   <li>文本去重：完全相同的子查询只保留一份</li>
     *   <li>数量上限：超过参数管理 {@code queryRewrite.maxSubQueries}（兜底 3，硬上限 5）
     *       则丢弃拆分，回退为单查询</li>
     *   <li>主查询与子查询分离：subQueries 列表只存真正的子查询，不包含主查询</li>
     * </ol>
     *
     * <p>语义多样性校验需要额外 embedding 调用，放在调用方（rewrite 方法）中进行。</p>
     */
    private SubQueryParseResult parseSubQueries(String llmResponse) {
        String trimmed = llmResponse.trim();
        if (!trimmed.contains("|")) {
            return new SubQueryParseResult(trimmed, Collections.emptyList());
        }

        String[] parts = trimmed.split("\\|");
        // 文本去重（保持顺序）
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String part : parts) {
            String p = part.trim();
            if (!p.isEmpty()) {
                dedup.add(p);
            }
        }

        if (dedup.isEmpty()) {
            return new SubQueryParseResult(trimmed, Collections.emptyList());
        }

        List<String> uniqueList = new ArrayList<>(dedup);

        // 数量上限：参数管理可调，收敛到 [1, HARD_MAX_SUB_QUERIES]
        int maxSubQueries = paramValueProvider.getInt(
                "queryRewrite.maxSubQueries", DEFAULT_MAX_SUB_QUERIES);
        maxSubQueries = Math.max(1, Math.min(maxSubQueries, HARD_MAX_SUB_QUERIES));

        // 超过上限：丢弃拆分，全部内容拼成一个单查询
        if (uniqueList.size() > maxSubQueries) {
            log.info("子查询数量({})超过上限({})，丢弃拆分回退单查询", uniqueList.size(), maxSubQueries);
            return new SubQueryParseResult(String.join(" | ", uniqueList), Collections.emptyList());
        }

        String mainQuery = uniqueList.get(0);
        // subQueries 只存真正的子查询（去掉主查询）
        List<String> subQueries = uniqueList.size() > 1
                ? uniqueList.subList(1, uniqueList.size())
                : Collections.emptyList();

        return new SubQueryParseResult(mainQuery, subQueries);
    }

    /**
     * 子查询语义多样性校验
     *
     * <p>对所有子查询两两计算 embedding cosine similarity，
     * 若存在高度重复（> {@link #SUB_QUERY_DIVERSITY_THRESHOLD}）的子查询，
     * 说明 LLM 过度拆分，此时丢弃子查询列表，仅保留主查询。</p>
     *
     * @return true 表示子查询语义多样可采纳；false 表示存在重复应丢弃
     */
    private boolean validateSubQueryDiversity(List<String> subQueries) {
        if (subQueries == null || subQueries.size() < 2) {
            return true;
        }
        try {
            List<float[]> vectors = new ArrayList<>(subQueries.size());
            for (String sq : subQueries) {
                vectors.add(embeddingModel.embed(sq).content().vector());
            }
            for (int i = 0; i < vectors.size(); i++) {
                for (int j = i + 1; j < vectors.size(); j++) {
                    double sim = cosineSimilarity(vectors.get(i), vectors.get(j));
                    if (sim > SUB_QUERY_DIVERSITY_THRESHOLD) {
                        log.info("子查询语义重复 ({:.3f} > {:.2f})，丢弃拆分: \"{}\" vs \"{}\"",
                                sim, SUB_QUERY_DIVERSITY_THRESHOLD,
                                subQueries.get(i), subQueries.get(j));
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("子查询语义校验失败，保守采纳: {}", e.getMessage());
        }
        return true;
    }

    /**
     * 子查询解析结果
     */
    private static class SubQueryParseResult {
        final String mainQuery;
        final List<String> subQueries;

        SubQueryParseResult(String mainQuery, List<String> subQueries) {
            this.mainQuery = mainQuery;
            this.subQueries = subQueries;
        }
    }

    /**
     * 从改写结果中提取扩展关键词（| 分隔的子查询中的关键词）
     */
    private List<String> extractExpandKeywords(String rewritten) {
        if (!rewritten.contains("|")) {
            return Collections.emptyList();
        }
        String[] parts = rewritten.split("\\|");
        List<String> keywords = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= 20) {
                keywords.add(trimmed);
            }
        }
        return keywords;
    }

    private QueryRewriteResult fallback(String query) {
        return QueryRewriteResult.builder()
                .rewrittenQuery(query)
                .expandKeywords(Collections.emptyList())
                .excludeKeywords(Collections.emptyList())
                .confidence(0.50)
                .path(RewritePath.L1_RULE)  // 标记为降级
                .build();
    }
}