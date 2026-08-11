package com.hai.aiknowledgebase.queryrewrite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hai.aiknowledgebase.dto.QueryRewriteResult;
import com.hai.aiknowledgebase.dto.RewritePath;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
            你是一个查询改写助手，你的任务是将用户查询改写为更适合知识库检索的形式。
            禁止回答问题、禁止生成解释、禁止输出任何非改写结果的内容。

            规则：
            1. 对比类问题（含"对比/区别/差别/不同/差异/vs"等）必须拆分为多个子查询，用 | 分隔
               拆分时每个比较对象单独成子查询，每个比较维度单独成子查询，最后追加对比子查询
               例如："A和B在X和Y上的区别" → "A的X | A的Y | B的X | B的Y | A和B在X和Y上的对比"
            2. 并列多问问题（含"以及/还有/另外"等）必须拆分为多个子查询，用 | 分隔
            3. 展开缩写和简称（如"专升本"→"专升本考试"）
            4. 补充隐含上下文（如"它的配置"→根据上下文补充具体对象）
            5. 保留原始查询的核心语义，不要添加原文没有的信息
            6. 如果查询简单且无需拆分，直接返回原文

            禁止任何解释、禁止任何回答、禁止markdown格式。只返回改写结果。""";

    @Autowired(required = false)
    private OllamaChatModel localRewriteModel;

    private final EmbeddingModel embeddingModel;

    @Value("${query-rewrite.local-llm.fidelity-threshold:0.75}")
    private double fidelityThreshold;

    @Value("${query-rewrite.local-llm.rewrite-timeout-ms:10000}")
    private long rewriteTimeoutMs;

    @Value("${query-rewrite.local-llm.enabled:false}")
    private boolean enabled;

    /** 改写缓存：query → 改写结果 */
    private final Cache<String, QueryRewriteResult> cache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /** 复用 AsyncConfig 配置的线程池，用于 LLM 调用的超时控制 */
    private final ThreadPoolTaskExecutor taskExecutor;

    public LocalLLMRewriter(EmbeddingModel embeddingModel, ThreadPoolTaskExecutor taskExecutor) {
        this.embeddingModel = embeddingModel;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 判断本地 LLM 改写是否可用
     */
    public boolean isAvailable() {
        return enabled && localRewriteModel != null;
    }

    /**
     * 执行查询改写
     *
     * @param query    原始查询
     * @param history  对话历史（可选，用于指代消解上下文）
     * @return 改写结果
     */
    public QueryRewriteResult rewrite(String query, List<String> history) {
        if (!isAvailable()) {
            return fallback(query);
        }

        // 1. 检查缓存
        QueryRewriteResult cached = cache.getIfPresent(query);
        if (cached != null) {
            log.debug("改写缓存命中: {} → {}", query, cached.getRewrittenQuery());
            return cached;
        }

        // 2. 构建改写 Prompt
        String prompt = buildPrompt(query, history);

        // 3. 调用本地 LLM（带超时）
        String rewritten;
        try {
            rewritten = callWithTimeout(prompt);
        } catch (Exception e) {
            log.warn("本地 LLM 改写失败/超时，回退到原查询: {}", e.getMessage());
            return fallback(query);
        }

        // 4. 后处理 + 子查询提取
        SubQueryParseResult parseResult = parseSubQueries(rewritten);
        rewritten = postProcess(parseResult.mainQuery, query);

        // 4.5 回答式输出检测：小模型可能无视指令直接回答问题，而非改写
        if (isAnswerInsteadOfRewrite(rewritten, query)) {
            log.warn("本地 LLM 未按要求改写(疑似直接回答)，回退到原查询: {} → {}",
                    query, rewritten.substring(0, Math.min(80, rewritten.length())));
            return fallback(query);
        }

        // 5. 保真度校验
        // 分解场景：用全部子查询拼接文本计算保真度，避免单个子查询与原查询语义范围不匹配
        String fidelityText = parseResult.subQueries.size() > 1
                ? String.join(" ", parseResult.subQueries)
                : rewritten;
        double fidelity = computeFidelity(query, fidelityText);
        if (fidelity < fidelityThreshold) {
            log.info("改写保真度不足 ({} < {})，回退到原查询: {} → {}",
                    String.format("%.3f", fidelity), fidelityThreshold, query, rewritten);
            return fallback(query);
        }

        // 6. 构建结果
        QueryRewriteResult result = QueryRewriteResult.builder()
                .rewrittenQuery(rewritten)
                .subQueries(parseResult.subQueries)
                .expandKeywords(extractExpandKeywords(rewritten))
                .excludeKeywords(Collections.emptyList())
                .confidence(fidelity)
                .path(RewritePath.L3_LLM)
                .build();

        cache.put(query, result);
        log.info("本地 LLM 改写: {} → {} (子查询: {}, 保真度: {})",
                query, rewritten, parseResult.subQueries.size(), String.format("%.3f", fidelity));
        return result;
    }

    // ==================== 私有方法 ====================

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
        Future<String> future = taskExecutor.submit(() ->
                localRewriteModel.chat(
                        dev.langchain4j.data.message.SystemMessage.from(REWRITE_SYSTEM_PROMPT),
                        dev.langchain4j.data.message.UserMessage.from(prompt)
                ).aiMessage().text()
        );
        try {
            return future.get(rewriteTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("本地 LLM 改写超时 ({}ms)", rewriteTimeoutMs);
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

    /**
     * 从 LLM 返回的文本中解析子查询（| 分隔）
     *
     * <p>LLM 按 Prompt 约定用 | 分隔多个子查询时，
     * 第一个作为主查询（rewrittenQuery），其余作为子查询列表（subQueries）。
     * 如果没有 | 分隔符，整个文本作为唯一查询，subQueries 为空。</p>
     */
    private SubQueryParseResult parseSubQueries(String llmResponse) {
        String trimmed = llmResponse.trim();
        if (!trimmed.contains("|")) {
            return new SubQueryParseResult(trimmed, Collections.emptyList());
        }

        String[] parts = trimmed.split("\\|");
        List<String> subQueries = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (!part.isEmpty()) {
                subQueries.add(part);
            }
        }

        if (subQueries.isEmpty()) {
            return new SubQueryParseResult(trimmed, Collections.emptyList());
        }

        String mainQuery = subQueries.get(0);
        return new SubQueryParseResult(mainQuery, subQueries);
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