package com.hai.aiknowledgebase.queryrewrite;

import com.hai.aiknowledgebase.dto.CustomChatMessage;
import com.hai.aiknowledgebase.dto.RewriteStrategyEnum;
import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 按需改写路由分类器
 *
 * <h2>功能概述</h2>
 * 规则为主 + 嵌入辅助的混合分类引擎，根据查询特征判定应执行的改写策略。
 * <ul>
 *   <li>规则强命中（≥0.5）：< 1ms</li>
 *   <li>嵌入辅助（灰区 0.1~0.4）：~5ms</li>
 *   <li>降级纯规则（嵌入失败时）：< 1ms</li>
 * </ul>
 *
 * <h2>策略说明</h2>
 * <pre>
 *   DIRECT        → 直接检索，不做改写
 *   CORRECT_ONLY  → 仅纠错
 *   RESOLVE_ONLY  → 纠错 + 指代消解
 *   SIMPLE_REWRITE→ 纠错 + 指代消解 + L1/L2 改写
 *   DECOMPOSE     → 纠错 + 指代消解 + 问题分解
 * </pre>
 *
 * <h2>路由决策树</h2>
 * <pre>
 *   空或极短查询 → DIRECT
 *   有指代词 + 有历史 → RESOLVE_ONLY / DECOMPOSE
 *   复合问题 → DECOMPOSE
 *   简单查询 → DIRECT
 *   默认 → SIMPLE_REWRITE
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRouter {

    private final ChineseTokenizerService tokenizerService;
    private final EmbeddingModel embeddingModel;

    /** 嵌入辅助判定超时时间（毫秒） */
    @Value("${query-router.embedding-timeout-ms:5000}")
    private long embeddingTimeoutMs;

    /** 规则评分强命中阈值（≥此值直接判定复合） */
    @Value("${query-router.rule-strong-threshold:0.5}")
    private double ruleStrongThreshold;

    /** 规则弱信号阈值（≥此值时嵌入辅助用宽松阈值） */
    @Value("${query-router.rule-weak-signal-threshold:0.1}")
    private double ruleWeakSignalThreshold;

    /** 嵌入辅助：有弱规则信号时的相似度差值阈值 */
    @Value("${query-router.embedding-diff-threshold-weak:-0.02}")
    private double embeddingDiffThresholdWeak;

    /** 嵌入辅助：无规则信号时的相似度差值阈值 */
    @Value("${query-router.embedding-diff-threshold-none:0.08}")
    private double embeddingDiffThresholdNone;

    /** 规则评分：多问句/多疑问段权重 */
    @Value("${query-router.score-weight.multi-question:0.5}")
    private double scoreWeightMultiQuestion;

    /** 规则评分：对比/隐式对比权重 */
    @Value("${query-router.score-weight.compare:0.4}")
    private double scoreWeightCompare;

    /** 规则评分：枚举/多方面权重 */
    @Value("${query-router.score-weight.enum-aspect:0.3}")
    private double scoreWeightEnumAspect;
    // ==================== 正则常量 ====================

    /** 指代词正则：检测"它/这个/那个/上面/前面/这里/那里/这些/那些/该/其"等 */
    private static final Pattern PRONOUN_PATTERN = Pattern.compile(
            "它(?:们)?|上面|前面|这里|那里|这些|那些|" +
                    "该(?:文档|文件|系统|项目|功能|模块|方法|类|接口|服务|配置|组件)?|" +
                    "其(?:中)?|这(?:个|些)?|那(?:个|些)?"
    );

    // ==================== 复合问题关键词 ====================

    /** 对比类关键词：触发问题分解 */
    private static final Set<String> COMPARE_KEYWORDS = Set.of(
            "区别", "对比", "比较", "不同", "差异", "优缺点", "vs", "VS",
            "相比", "哪个更好", "哪个更", "异同", "各有什么", "分别是什么"
    );

    /** 枚举类关键词：表示多个并列实体或问题 */
    private static final Set<String> ENUM_KEYWORDS = Set.of(
            "分别", "各自", "以及", "还有", "另外", "同时", "此外"
    );

    /** 对比词排除表：含这些词时不触发对比规则（如"对比表格格式"是操作指令而非知识查询） */
    private static final Set<String> COMPARE_EXCLUDE_WORDS = Set.of(
            "表格", "格式", "样式", "排版", "布局", "字体", "颜色", "对齐", "图表", "代码"
    );

    /** 多实体连词：检测并列实体 */
    private static final Set<String> ENTITY_CONJUNCTIONS = Set.of(
            "和", "与", "以及", "还有", "或者", "及"
    );

    /** 隐式对比词：多实体 + 以下词 = 隐式对比查询 */
    private static final Set<String> IMPLICIT_COMPARE_WORDS = Set.of(
            "哪个", "哪种", "选择", "优劣", "好坏", "适合", "推荐", "更好", "最好"
    );

    /** 方面关键词：≥2 个表示多维度查询 */
    private static final Set<String> ASPECT_KEYWORDS = Set.of(
            "特点", "原理", "用法", "优缺点", "场景", "步骤", "方法", "功能",
            "作用", "意义", "区别", "联系", "概念", "定义", "分类", "类型",
            "优势", "劣势", "用途", "实现", "架构", "流程"
    );

    /** 疑问词集合：用于多问句分段检测 */
    private static final Set<String> QUESTION_WORDS = Set.of(
            "如何", "怎么", "为什么", "怎样", "多少", "是否", "能不能", "是什么", "有什么"
    );

    /** 疑问词分段正则：按标点拆分后检测各段是否含疑问词 */
    private static final Pattern SEGMENT_SPLIT_PATTERN = Pattern.compile("[，。；、,;]");

    // ==================== 嵌入质心原型文本 ====================

    /** 复合问题原型文本（启动时编码为质心向量） */
    private static final List<String> COMPOUND_PROTOTYPES = List.of(
            "什么是Java，它有什么特点",
            "Redis和Memcached的区别",
            "Spring和SpringBoot哪个更适合微服务",
            "MySQL的索引类型和优化方法",
            "如果系统宕机了怎么恢复，数据会不会丢失",
            "Java Python Go各自的并发模型",
            "分布式系统的优缺点和适用场景",
            "A和B有什么不同",
            "对比一下REST和GraphQL",
            "Kafka和RabbitMQ各自的优缺点",
            "什么是Docker，怎么安装和使用",
            "微服务和单体架构的区别和选择",
            "React和Vue哪个好",
            "TCP和UDP的区别以及各自的应用场景",
            "Git和SVN的对比分析",
            "Java和C++的性能对比",
            "什么是云原生，它有哪些核心技术"
    );

    /** 简单查询原型文本（启动时编码为质心向量） */
    private static final List<String> SIMPLE_PROTOTYPES = List.of(
            "什么是Java",
            "Redis介绍",
            "SpringBoot怎么用",
            "MySQL索引",
            "如何安装Docker",
            "Python列表操作",
            "什么是微服务",
            "Docker是什么",
            "Git基本操作",
            "Java特点",
            "REST API设计",
            "Kafka使用教程",
            "TCP协议介绍",
            "React入门",
            "Linux常用命令",
            "Python安装方法",
            "什么是容器",
            "MySQL优化建议"
    );

    // ==================== 嵌入质心（启动时初始化） ====================

    /** 复合问题质心向量（归一化） */
    private float[] compoundCentroid;

    /** 简单查询质心向量（归一化） */
    private float[] simpleCentroid;

    /** 嵌入辅助是否可用 */
    private volatile boolean embeddingAssistAvailable = false;

    // ==================== 初始化 ====================

    @PostConstruct
    void initCentroids() {
        try {
            log.info("初始化复合问题检测嵌入质心...");

            float[][] compoundVectors = new float[COMPOUND_PROTOTYPES.size()][];
            for (int i = 0; i < COMPOUND_PROTOTYPES.size(); i++) {
                compoundVectors[i] = embeddingModel.embed(COMPOUND_PROTOTYPES.get(i)).content().vector();
            }

            float[][] simpleVectors = new float[SIMPLE_PROTOTYPES.size()][];
            for (int i = 0; i < SIMPLE_PROTOTYPES.size(); i++) {
                simpleVectors[i] = embeddingModel.embed(SIMPLE_PROTOTYPES.get(i)).content().vector();
            }

            compoundCentroid = computeCentroid(compoundVectors);
            simpleCentroid = computeCentroid(simpleVectors);

            embeddingAssistAvailable = true;
            log.info("嵌入质心初始化完成，维度={}", compoundCentroid.length);
        } catch (Exception e) {
            log.warn("嵌入质心初始化失败，将降级为纯规则判断: {}", e.getMessage());
            embeddingAssistAvailable = false;
        }
    }

    /**
     * 计算质心向量（均值 + L2 归一化）
     */
    private float[] computeCentroid(float[][] vectors) {
        if (vectors == null || vectors.length == 0) {
            throw new IllegalArgumentException("vectors 不能为空");
        }
        int dim = vectors[0].length;
        float[] centroid = new float[dim];
        for (float[] vec : vectors) {
            for (int i = 0; i < dim; i++) {
                centroid[i] += vec[i];
            }
        }
        for (int i = 0; i < dim; i++) {
            centroid[i] /= vectors.length;
        }
        float norm = 0f;
        for (float v : centroid) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) {
                centroid[i] /= norm;
            }
        }
        return centroid;
    }

    // ==================== 主入口 ====================

    /**
     * 路由策略选择（简化版）
     *
     * <p>本地 LLM 改写后，路由仅需判断是否需要指代消解和问题分解，
     * 不再需要规则引擎的复杂路由逻辑。</p>
     */
    public RewriteStrategyEnum route(String query, List<CustomChatMessage> history) {
        // 有对话历史 + 包含代词 → RESOLVE_ONLY（纠错+消解后交给 LLM 改写）
        if (history != null && !history.isEmpty() && containsPronouns(query)) {
            return RewriteStrategyEnum.RESOLVE_ONLY;
        }

        // 默认：SIMPLE_REWRITE（纠错 + LLM 改写）
        return RewriteStrategyEnum.SIMPLE_REWRITE;
    }

    /**
     * 检测查询中是否包含中文指代词
     * <p>匹配：它/它们、这个/那个、上面/前面、这里/那里、
     * 这些/那些、该文档/该模块/该配置等、其/其中</p>
     */
    private boolean containsPronouns(String query) {
        return PRONOUN_PATTERN.matcher(query).find();
    }

    // ==================== 判定规则 ====================

    /**
     * 复合问题判定（规则为主 + 嵌入辅助）
     *
     * <p>判定流程：</p>
     * <ol>
     *   <li>快速通道：极短/极长查询直接判定</li>
     *   <li>增强规则评分：多问句、对比、多实体、枚举、多方面等维度</li>
     *   <li>嵌入辅助：规则灰区（0.1~0.4）时用嵌入相似度确认</li>
     *   <li>降级：嵌入失败时回退纯规则</li>
     * </ol>
     */
    private boolean isCompound(String query) {
        // 1. 快速通道
        int questionMarkCount = countChar(query, '？') + countChar(query, '?');
        if (questionMarkCount >= 3) {
            return true;
        }
        if (query.length() <= 6 && questionMarkCount <= 1 && !hasMultiEntity(query)) {
            return false;
        }

        // 2. 增强规则评分
        double ruleScore = ruleCompoundScore(query);
        if (ruleScore >= ruleStrongThreshold) {
            log.debug("复合问题规则强命中: score={}", ruleScore);
            return true;
        }

        // 3. 嵌入辅助（灰区 0.1~0.4 或无规则信号时）
        if (embeddingAssistAvailable) {
            try {
                boolean embeddingResult = isCompoundByEmbeddingWithTimeout(query, ruleScore);
                log.debug("嵌入辅助判定: ruleScore={}, result={}", ruleScore, embeddingResult);
                return embeddingResult;
            } catch (Exception e) {
                log.warn("嵌入辅助判定失败，降级为规则判断: {}", e.getMessage());
            }
        }

        // 4. 降级：纯规则
        return isCompoundByFallbackRules(query);
    }

    /**
     * 增强规则评分
     *
     * <p>评分维度：</p>
     * <ul>
     *   <li>多问号（≥2）：+0.5</li>
     *   <li>多疑问词分段（≥2段含疑问词）：+0.5</li>
     *   <li>对比关键词（含排除词过滤）：+0.4</li>
     *   <li>多实体 + 隐式对比词：+0.4</li>
     *   <li>枚举关键词：+0.3</li>
     *   <li>多方面关键词（≥2个）：+0.3</li>
     * </ul>
     */
    private double ruleCompoundScore(String query) {
        double score = 0.0;

        // 多问号
        int questionMarkCount = countChar(query, '？') + countChar(query, '?');
        if (questionMarkCount >= 2) {
            score += scoreWeightMultiQuestion;
        }

        // 多疑问词分段检测（如"什么是Java，它有什么特点"→2段含疑问词）
        int questionSegments = countQuestionSegments(query);
        if (questionSegments >= 2) {
            score += scoreWeightMultiQuestion;
        }

        // 对比关键词（含排除词过滤）
        if (containsAny(query, COMPARE_KEYWORDS) && !containsAny(query, COMPARE_EXCLUDE_WORDS)) {
            score += scoreWeightCompare;
        }

        // 多实体 + 隐式对比词
        if (hasMultiEntity(query) && containsAny(query, IMPLICIT_COMPARE_WORDS)) {
            score += scoreWeightCompare;
        }

        // 枚举关键词
        if (containsAny(query, ENUM_KEYWORDS)) {
            score += scoreWeightEnumAspect;
        }

        // 多方面关键词
        int aspectCount = countAspects(query);
        if (aspectCount >= 2) {
            score += scoreWeightEnumAspect;
        }

        return Math.min(score, 1.0);
    }

    /**
     * 带超时的嵌入辅助判定
     *
     * <p>使用 {@link CompletableFuture} 包装嵌入调用，超时后取消后台任务并降级为规则判断。</p>
     */
    private boolean isCompoundByEmbeddingWithTimeout(String query, double ruleScore) {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> isCompoundByEmbedding(query, ruleScore));
        try {
            return future.get(embeddingTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("嵌入辅助判定超时 ({}ms)，已取消后台任务，降级为规则判断", embeddingTimeoutMs);
            throw new RuntimeException("嵌入辅助判定超时", e);
        } catch (Exception e) {
            throw new RuntimeException("嵌入辅助判定异常", e);
        }
    }

    /**
     * 嵌入辅助判定
     *
     * <p>计算查询与复合/简单质心的余弦相似度差值：</p>
     * <ul>
     *   <li>规则评分 ≥ 0.1（弱信号）：差值 > -0.02 即判定复合</li>
     *   <li>规则评分 = 0（无信号）：差值 > 0.08 才判定复合</li>
     * </ul>
     */
    private boolean isCompoundByEmbedding(String query, double ruleScore) {
        float[] queryVector = embeddingModel.embed(query).content().vector();

        // L2 归一化
        float norm = 0f;
        for (float v : queryVector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < queryVector.length; i++) {
                queryVector[i] /= norm;
            }
        }

        // 计算与两个质心的余弦相似度
        double simCompound = cosineSimilarity(queryVector, compoundCentroid);
        double simSimple = cosineSimilarity(queryVector, simpleCentroid);
        double diff = simCompound - simSimple;

        // 根据规则信号强度调整阈值
        if (ruleScore >= ruleWeakSignalThreshold) {
            return diff > embeddingDiffThresholdWeak;
        } else {
            return diff > embeddingDiffThresholdNone;
        }
    }

    /**
     * 降级规则判定（嵌入失败时兜底）
     */
    private boolean isCompoundByFallbackRules(String query) {
        int questionMarkCount = countChar(query, '？') + countChar(query, '?');
        if (questionMarkCount >= 2) {
            return true;
        }

        if (containsAny(query, COMPARE_KEYWORDS) && !containsAny(query, COMPARE_EXCLUDE_WORDS)) {
            return true;
        }

        if (hasMultiEntity(query) && containsAny(query, IMPLICIT_COMPARE_WORDS)) {
            return true;
        }

        if (containsAny(query, ENUM_KEYWORDS) && query.length() > 20) {
            return true;
        }

        if (query.length() > 50) {
            return true;
        }

        try {
            List<String> tokens = tokenizerService.tokenize(query, false);
            return tokens.size() > 8;
        } catch (Exception e) {
            log.warn("分词失败，按长度判断: {}", e.getMessage());
            return query.length() > 50;
        }
    }

    /**
     * 简单查询判定
     *
     * <p>满足以下所有条件即为简单查询：</p>
     * <ul>
     *   <li>查询长度 ≤ 15 字符</li>
     *   <li>不包含指代词</li>
     *   <li>不包含复合关键词</li>
     *   <li>不包含疑问词（或仅以"什么是"开头且 ≤ 20 字）</li>
     *   <li>分词后 token 数 ≤ 3</li>
     * </ul>
     */
    private boolean isSimple(String query) {
        if (query.length() > 15) {
            return false;
        }

        if (PRONOUN_PATTERN.matcher(query).find()) {
            return false;
        }

        for (String kw : COMPARE_KEYWORDS) {
            if (query.contains(kw)) {
                return false;
            }
        }

        for (String kw : ENUM_KEYWORDS) {
            if (query.contains(kw)) {
                return false;
            }
        }

        if (query.startsWith("什么是") && query.length() <= 20) {
            return true;
        }

        if (isQuestionQuery(query)) {
            return false;
        }

        try {
            List<String> tokens = tokenizerService.tokenize(query, false);
            return tokens.size() <= 3;
        } catch (Exception e) {
            log.warn("分词失败，按长度判断: {}", e.getMessage());
            return query.length() <= 10;
        }
    }

    /**
     * 疑问句判定：是否包含常见疑问词
     */
    private boolean isQuestionQuery(String query) {
        return query.contains("如何") || query.contains("怎么") ||
                query.contains("为什么") || query.contains("怎样") ||
                query.contains("多少") || query.contains("是否") ||
                query.contains("能不能") || query.contains("是什么");
    }

    /**
     * 检测查询是否包含多个并列实体
     *
     * <p>检测模式：</p>
     * <ul>
     *   <li>显式连词：A和B、A与B、A以及B 等</li>
     *   <li>顿号枚举：A、B</li>
     *   <li>vs/VS 模式</li>
     * </ul>
     *
     * <p>对短连词（"和"/"与"/"及"）通过分词判断是否为独立 token，
     * 避免"和平""和谐"等词中的子串误判。</p>
     */
    private boolean hasMultiEntity(String query) {
        // 长连词直接 contains 检测（不会出现子串误判）
        if (query.contains("以及") || query.contains("还有") || query.contains("或者")) {
            return true;
        }
        // 顿号和 vs 模式
        if (query.contains("、")) {
            return true;
        }
        if (query.contains(" vs ") || query.contains(" VS ")) {
            return true;
        }
        // 短连词通过分词判断是否为独立 token
        try {
            List<String> tokens = tokenizerService.tokenize(query, false);
            for (String token : tokens) {
                if ("和".equals(token) || "与".equals(token) || "及".equals(token)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("分词失败，回退到 contains 检测短连词: {}", e.getMessage());
            // 降级：回退到 contains 检测
            if (query.contains("和") || query.contains("与") || query.contains("及")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 统计含疑问词的段数
     *
     * <p>将查询按标点分段，统计包含疑问词的段数。
     * 例如"什么是Java，它有什么特点"→2段含疑问词</p>
     */
    private int countQuestionSegments(String query) {
        String[] segments = SEGMENT_SPLIT_PATTERN.split(query);
        int count = 0;
        for (String seg : segments) {
            for (String qw : QUESTION_WORDS) {
                if (seg.contains(qw)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /**
     * 统计查询中方面关键词的出现数
     */
    private int countAspects(String query) {
        int count = 0;
        for (String kw : ASPECT_KEYWORDS) {
            if (query.contains(kw)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 检查查询是否包含集合中的任一关键词
     */
    private boolean containsAny(String query, Set<String> keywords) {
        for (String kw : keywords) {
            if (query.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算两个归一化向量的余弦相似度（即点积）
     */
    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    /**
     * 统计字符出现次数
     */
    private int countChar(String str, char ch) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) {
                count++;
            }
        }
        return count;
    }
}