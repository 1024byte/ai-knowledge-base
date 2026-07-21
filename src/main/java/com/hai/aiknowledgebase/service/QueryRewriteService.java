package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.*;
import com.hai.aiknowledgebase.interfaces.LocalQueryRewriter;
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
 *   ├─ L1 规则改写（固定映射 + 同义词替换）
 *   │   └─ 置信度 ≥ 0.85 → 直接返回
 *   ├─ L2 NLP 增强（纠错 → 分词 → 意图识别 → 策略选择 → 置信度计算）
 *   │   ├─ RULE_ONLY 模式 → 直接返回
 *   │   └─ 置信度 ≥ 0.70 → 返回
 *   └─ L3 LLM 改写（仅 FULL 模式，且 L2 不满足阈值时）
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

    // ==================== 预编译正则常量 ====================

    /**
     * 排除词提取正则
     * <p>
     * 匹配模式："不包含/除了/不要/排除/不含" + 空格 + 排除内容。
     * 排除内容支持中英文、顿号/逗号/"和"/"&" 分隔的多个词。
     * <p>
     * 示例：<br>
     * "不包含糖的饮料" → 提取 "糖"<br>
     * "除了苹果、香蕉" → 提取 "苹果", "香蕉"<br>
     * "不要Java和Python" → 提取 "Java", "Python"
     */
    private static final Pattern EXCLUDE_PATTERN = Pattern.compile(
            "(?:不包含|除了|不要|排除|不含)[\\s]*([\\u4e00-\\u9fa5a-zA-Z0-9]+(?:[、，,和&\\s]+[\\u4e00-\\u9fa5a-zA-Z0-9]+)*)"
    );

    /** 排除词助词分割正则：用于从排除词中剥离"的"、"了吗"等非关键词助词 */
    private static final Pattern EXCLUDE_PARTICLE_SPLIT = Pattern.compile("[的了吗呢吧啊呀嘛]+");

    /** 标点符号正则：用于清理查询文本中的中英文标点符号 */
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[，,、。.！!？?；;：:\"\u201C\u201D\"\"()（）]");

    // ==================== 主入口 ====================

    /**
     * 简化版入口：执行默认 RULE_ONLY 路由的查询改写
     *
     * <p>该方法内部构造 {@link RewriteRequest} 并设置 routingDecision 为
     * {@link RoutingDecision#RULE_ONLY}，因此<b>不会走 L3 LLM 改写路径</b>。
     * 如需完整三层 pipeline，请使用 {@link #rewrite(RewriteRequest)}。</p>
     *
     * @param query 原始用户查询文本
     * @return 改写结果，包含改写后查询、扩展关键词、排除关键词、置信度和改写路径
     */
    public QueryRewriteResult rewrite(String query) {
        RewriteRequest request = RewriteRequest.builder()
                .query(query)
                .routingDecision(RoutingDecision.RULE_ONLY)
                .build();
        return rewrite(request);
    }

    /**
     * 完整版入口：根据路由决策执行多层查询改写
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>前置检查</b>：改写开关、空值、路由决策</li>
     *   <li><b>L1 规则改写</b>：固定映射 + 同义词替换，置信度 ≥ 0.85 则直接返回</li>
     *   <li><b>L2 NLP 增强</b>：纠错 + 分词 + 意图识别 + 策略选择
     *       <ul>
     *         <li>RULE_ONLY 模式：L2 结果直接返回</li>
     *         <li>FULL 模式：置信度 ≥ 0.70 则返回，否则进入 L3</li>
     *       </ul>
     *   </li>
     *   <li><b>L3 LLM 改写</b>（仅 FULL 模式）：调用大模型改写
     *       <ul>
     *         <li>成功 → 返回 L3 结果</li>
     *         <li>失败/不可用 → 降级返回 L2 结果</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @param request 改写请求，包含查询文本、对话历史、路由决策和会话 ID
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
        RoutingDecision decision = request.getRoutingDecision();
        if (decision == null) {
            decision = RoutingDecision.RULE_ONLY;
        }

        long startTime = System.currentTimeMillis();

        if (decision == RoutingDecision.SKIP) {
            log.debug("意图路由判定 SKIP，跳过改写: {}", trimmed);
            return buildNoneResult(trimmed);
        }

        QueryRewriteResult l1Result = applyRuleRewrite(trimmed);
        if (l1Result.getConfidence() >= l1ConfidenceThreshold) {
            logRewriteResult("L1", trimmed, l1Result, startTime);
            return l1Result;
        }

        if (decision == RoutingDecision.RULE_ONLY) {
            QueryRewriteResult l2Result = applyNlpRewrite(trimmed, l1Result);
            logRewriteResult("L2(RULE_ONLY)", trimmed, l2Result, startTime);
            return l2Result;
        }

        QueryRewriteResult l2Result = applyNlpRewrite(trimmed, l1Result);
        if (l2Result.getConfidence() >= l2ConfidenceThreshold) {
            logRewriteResult("L2", trimmed, l2Result, startTime);
            return l2Result;
        }

        if (llmRewriter != null) {
            try {
                QueryRewriteResult l3Result = llmRewriter.rewrite(trimmed);
                if (l3Result != null && l3Result.isRewritten()) {
                    logRewriteResult("L3", trimmed, l3Result, startTime);
                    return l3Result;
                }
            } catch (Exception e) {
                log.error("L3 LLM 改写失败，降级使用 L2 结果: {}", e.getMessage());
            }
        }

        logRewriteResult("L2(L3降级)", trimmed, l2Result, startTime);
        return l2Result;
    }

    // ==================== L1：安全替换器（区间检测） ====================

    /**
     * L1 规则改写（Rule-based Rewrite）
     *
     * <h3>核心策略：统一候选列表 + 区间占用检测</h3>
     * 将固定映射和同义词统一抽象为候选列表，按长度降序排序，确保长词优先匹配，
     * 彻底解决短词误伤长词的问题（如"API"优先匹配"API网关"而非单独的"API"）。
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>构建候选列表</b>：合并固定映射和同义词为统一 Candidate 列表，
     *       按 key 长度降序排序，同长度时固定映射优先</li>
     *   <li><b>统一匹配</b>：遍历候选列表，使用 {@link #isOverlapping(List, int, int)}
     *       检测区间占用，避免重复替换同一位置</li>
     *   <li><b>执行替换</b>：从后往前替换，避免索引偏移（后替换的不影响前面位置的索引）</li>
     *   <li><b>收集扩展关键词</b>：提取同义词词典中命中的词 + 简单关键词</li>
     *   <li><b>提取排除词</b>：正则匹配"不包含/除了/不要"等模式</li>
     *   <li><b>计算置信度</b>：固定映射命中 → 取映射配置的置信度上限；
     *       同义词命中 → 0.80；有替换但无命中 → 0.70；无替换 → 0.50</li>
     * </ol>
     *
     * <h3>设计说明</h3>
     * <ul>
     *   <li>同义词改写格式为 "key + 空格 + firstSynonym"，如"计算机 电脑"，
     *       保留原始词的同时追加同义词，便于向量检索覆盖更多语义</li>
     *   <li>区间占用检测确保同一位置不会被多个规则重复替换</li>
     *   <li>固定映射每词只替换第一次出现（break 机制），避免重复替换</li>
     * </ul>
     *
     * @param query 原始查询文本（已 trim）
     * @return L1 改写结果
     */
    private QueryRewriteResult applyRuleRewrite(String query) {
        Map<String, String> fixedMapping = configLoader.getFixedMapping();
        Map<String, Double> fixedConfidenceMap = configLoader.getFixedMappingConfidence();
        Map<String, List<String>> synonymDict = configLoader.getSynonymDict();

        // ========== 1. 统一候选列表（固定映射 + 同义词） ==========

        List<Candidate> candidates = new ArrayList<>();

        // 1.1 固定映射候选
        for (Map.Entry<String, String> entry : fixedMapping.entrySet()) {
            String key = entry.getKey();
            candidates.add(new Candidate(
                    key,
                    entry.getValue(),
                    true,  // isFixedMapping
                    fixedConfidenceMap.getOrDefault(key, 0.95)
            ));
        }

        // 1.2 同义词候选
        for (Map.Entry<String, List<String>> entry : synonymDict.entrySet()) {
            String key = entry.getKey();
            List<String> synonyms = entry.getValue();
            if (synonyms == null || synonyms.isEmpty()) {
                continue;
            }
            String firstSynonym = synonyms.get(0);
            if (key.equals(firstSynonym)) {
                continue; // 同义词就是自己，跳过
            }
            candidates.add(new Candidate(
                    key,
                    key + " " + firstSynonym,
                    false, // isFixedMapping
                    0.80   // 同义词置信度
            ));
        }

        candidates.sort((a, b) -> {
            int lenCmp = Integer.compare(b.key.length(), a.key.length());
            if (lenCmp != 0) return lenCmp;
            // 长度相同，固定映射优先
            if (a.isFixedMapping != b.isFixedMapping) {
                return a.isFixedMapping ? -1 : 1;
            }
            return 0;
        });
        // ========== 2. 统一匹配（共享区间占用集合） ==========

        List<Replacement> replacements = new ArrayList<>();
        List<Interval> occupiedIntervals = new ArrayList<>();
        boolean fixedMappingHit = false;
        boolean synonymHit = false;

        for (Candidate candidate : candidates) {
            String key = candidate.key;
            int searchStart = 0;
            int index;
            boolean matched = false;

            while ((index = query.indexOf(key, searchStart)) >= 0) {
                int end = index + key.length();

                // 检查是否与已占用区间重叠
                if (!isOverlapping(occupiedIntervals, index, end)) {
                    occupiedIntervals.add(new Interval(index, end));
                    replacements.add(new Replacement(index, end, candidate.replacement));
                    if (candidate.isFixedMapping) {
                        fixedMappingHit = true;
                    } else {
                        synonymHit = true;
                    }
                    matched = true;
                    break;
                }
                searchStart = index + 1;
            }
        }

        // ========== 3. 执行替换（从后往前，避免索引偏移） ==========

        replacements.sort((a, b) -> Integer.compare(b.start, a.start));

        StringBuilder resultBuilder = new StringBuilder(query);
        for (Replacement r : replacements) {
            resultBuilder.replace(r.start, r.end, r.replacement);
        }
        String result = resultBuilder.toString();

        // ========== 4. 收集扩展关键词 ==========

        List<String> allExpandKeywords = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : synonymDict.entrySet()) {
            if (query.contains(entry.getKey())) {
                allExpandKeywords.addAll(entry.getValue());
            }
        }

        List<String> keywords = extractSimpleKeywords(result);
        allExpandKeywords.addAll(0, keywords);
        List<String> expandKeywords = allExpandKeywords.stream().distinct().toList();

        // ========== 5. 排除词 ==========

        List<String> excludeKeywords = extractExcludeKeywords(query);

        // ========== 6. 置信度 ==========

        double confidence;
        if (fixedMappingHit) {
            confidence = getFixedMappingMaxConfidence(query, fixedConfidenceMap);
        } else if (synonymHit) {
            confidence = 0.80;
        } else if (!result.equals(query)) {
            confidence = 0.70;
        } else {
            confidence = 0.50;
        }

        return QueryRewriteResult.builder()
                .rewrittenQuery(result)
                .expandKeywords(expandKeywords)
                .excludeKeywords(excludeKeywords)
                .confidence(confidence)
                .path(RewritePath.L1_RULE)
                .build();
    }

// ==================== L1 辅助方法 ====================

    /**
     * 检查区间是否与任何已占用区间重叠
     *
     * <p>两个区间 [start1, end1) 和 [start2, end2) 不重叠的条件是：
     * end1 ≤ start2 或 start1 ≥ end2。取反即为重叠条件。</p>
     *
     * @param intervals 已占用区间列表
     * @param start     待检查的起始位置（含）
     * @param end       待检查的结束位置（不含）
     * @return true 表示与至少一个已占用区间重叠
     */
    private boolean isOverlapping(List<Interval> intervals, int start, int end) {
        for (Interval interval : intervals) {
            if (!(end <= interval.start || start >= interval.end)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取命中的固定映射中最高的置信度
     *
     * <p>遍历所有固定映射的 key，检查是否在查询中出现（子串匹配），
     * 取所有命中 key 的置信度最大值。</p>
     *
     * <p><b>注意：</b>使用 {@link String#contains(CharSequence)} 做子串匹配，
     * 可能导致短 key 误匹配长词（如"数据"匹配"数据库"）。
     * 在 L1 上下文中，此方法用于计算已命中固定映射的置信度上限，
     * 且 key 来自固定映射（通常为精确词），因此误匹配概率较低。</p>
     *
     * @param query         原始查询文本
     * @param confidenceMap key → 置信度的映射
     * @return 最大置信度，无命中时返回默认值 0.95
     */
    private double getFixedMappingMaxConfidence(String query, Map<String, Double> confidenceMap) {
        if (confidenceMap == null || confidenceMap.isEmpty()) {
            return 0.95;
        }
        double maxConf = 0.0;
        for (Map.Entry<String, Double> entry : confidenceMap.entrySet()) {
            if (query.contains(entry.getKey())) {
                maxConf = Math.max(maxConf, entry.getValue());
            }
        }
        return maxConf > 0.0 ? maxConf : 0.95;
    }

// ==================== 内部类 ====================

    /**
     * 区间类：记录已占用的字符区间 [start, end)
     *
     * <p>用于 L1 规则改写的区间占用检测，确保同一字符位置
     * 不会被多个候选规则重复替换。</p>
     *
     * @param start 起始位置（含）
     * @param end   结束位置（不含）
     */
    private static class Interval {
        final int start;
        final int end;

        Interval(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * 替换片段：记录一次匹配替换的范围和内容
     *
     * <p>用于 L1 规则改写，存储需要替换的区间和替换后的文本。
     * 替换按 start 降序排序后从后往前执行，避免索引偏移。</p>
     *
     * @param start       替换起始位置（含）
     * @param end         替换结束位置（不含）
     * @param replacement 替换后的文本
     */
    private static class Replacement {
        final int start;
        final int end;
        final String replacement;

        Replacement(int start, int end, String replacement) {
            this.start = start;
            this.end = end;
            this.replacement = replacement;
        }
    }

    /**
     * 候选替换项：L1 规则改写的统一抽象
     *
     * <p>将固定映射和同义词统一为候选列表，便于统一排序和匹配。
     * 按 key 长度降序排序，确保长词优先匹配，避免短词误伤长词。</p>
     *
     * @param key           匹配键（原始词）
     * @param replacement   替换文本
     * @param isFixedMapping 是否为固定映射（固定映射优先级高于同义词）
     * @param confidence    该候选项的置信度
     */
    private static class Candidate {
        final String key;
        final String replacement;
        final boolean isFixedMapping;
        final double confidence;

        Candidate(String key, String replacement, boolean isFixedMapping, double confidence) {
            this.key = key;
            this.replacement = replacement;
            this.isFixedMapping = isFixedMapping;
            this.confidence = confidence;
        }
    }

    // ==================== L2：NLP 增强（分词 + 意图识别 + 纠错） ====================

    /**
     * L2 NLP 增强改写（NLP-enhanced Rewrite）
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>查询纠错</b>：调用 {@link QueryCorrector#correct(String)} 对 L1 结果进行拼写纠错</li>
     *   <li><b>中文分词</b>：调用 {@link ChineseTokenizerService#tokenize(String, boolean)} 全模式分词</li>
     *   <li><b>意图识别</b>：调用 {@link IntentRecognitionOrchestrator#recognize(String)} 识别查询意图</li>
     *   <li><b>关键词提取</b>：调用 {@link ChineseTokenizerService#extractKeywords(String, int)} 提取 Top-5 关键词</li>
     *   <li><b>合并扩展关键词</b>：继承 L1 扩展关键词，合并 L2 提取的关键词，去重</li>
     *   <li><b>同义词扩展</b>：对每个分词 token 查找同义词词典，追加同义词</li>
     *   <li><b>意图策略选择</b>：根据意图类型选择对应的改写策略和追加词</li>
     *   <li><b>继承排除词</b>：直接从 L1 结果中继承排除关键词</li>
     *   <li><b>置信度计算</b>：四维加权（关键词数量 + 意图明确度 + 同义词命中 + 分词质量）</li>
     * </ol>
     *
     * @param originalQuery 原始查询文本（L1 之前的查询）
     * @param l1Result      L1 规则改写结果
     * @return L2 增强改写结果
     */
    private QueryRewriteResult applyNlpRewrite(String originalQuery, QueryRewriteResult l1Result) {
        // 1. 查询纠错
        String correctedQuery = queryCorrector.correct(l1Result.getRewrittenQuery());

        // 2. 中文分词
        List<String> tokens = tokenizerService.tokenize(correctedQuery, true);

        // 3. 意图识别（策略链：规则引擎 → LLM 兜底）
        IntentResult intentResult = orchestrator.recognize(correctedQuery);
        QueryIntent intent = intentResult.primaryIntent();
        log.debug("L2 意图识别结果: {} (置信度: {}) | 分词: {}", intent, intentResult.confidence(), tokens);

        // 4. 关键词提取（基于分词结果）
        List<String> keywords = tokenizerService.extractKeywords(correctedQuery, 5);

        // 5. 合并 L1 扩展关键词
        List<String> mergedKeywords = new ArrayList<>();
        if (l1Result.getExpandKeywords() != null && !l1Result.getExpandKeywords().isEmpty()) {
            mergedKeywords.addAll(l1Result.getExpandKeywords());
        }
        mergedKeywords.addAll(keywords);
        mergedKeywords = mergedKeywords.stream().distinct().collect(Collectors.toList());

        // 6. 同义词扩展
        List<String> synonymExpanded = expandSynonyms(tokens);

        // 7. 按意图选择改写策略
        RewriteStrategy strategy = selectStrategy(intent, correctedQuery, keywords, synonymExpanded);

        // 7.5 合并 LLM 改写提示词到扩展关键词（提升检索召回率）
        if (intentResult.rewriteHints() != null && !intentResult.rewriteHints().isEmpty()) {
            strategy.expandKeywords.addAll(intentResult.rewriteHints());
            // 去重：改写提示词可能与既有扩展词重复（final 字段不能重新赋值，用 clear + addAll 原地去重）
            List<String> deduped = strategy.expandKeywords.stream().distinct().collect(Collectors.toList());
            strategy.expandKeywords.clear();
            strategy.expandKeywords.addAll(deduped);
            log.debug("L2 合并 LLM 改写提示词: {}", intentResult.rewriteHints());
        }

        // 8. 继承 L1 排除词
        List<String> excludeKeywords = new ArrayList<>();
        if (l1Result.getExcludeKeywords() != null && !l1Result.getExcludeKeywords().isEmpty()) {
            excludeKeywords.addAll(l1Result.getExcludeKeywords());
        }

        // 9. 计算置信度（四维加权）
        double confidence = calculateL2Confidence(mergedKeywords, intent, tokens);

        return QueryRewriteResult.builder()
                .rewrittenQuery(strategy.rewrittenQuery)
                .expandKeywords(strategy.expandKeywords)
                .excludeKeywords(excludeKeywords)
                .confidence(confidence)
                .path(RewritePath.L2_NLP)
                .build();
    }

    /**
     * 同义词扩展：对分词结果中的每个词查找同义词
     *
     * <p>遍历所有 token，在 {@link QueryRewriteConfigLoader#getSynonymDict()} 中查找，
     * 将命中的同义词列表追加到结果中，最后去重返回。</p>
     *
     * <p>注意：同义词词典的 key 是原始词，value 是包含该词在内的同义词列表。
     * 因此命中后追加的是整个同义词组（包括原始词），调用方会通过
     * {@link #buildRewrittenQuery} 中的 contains 检查去重。</p>
     *
     * @param tokens 分词结果列表
     * @return 去重后的同义词列表
     */
    private List<String> expandSynonyms(List<String> tokens) {
        Map<String, List<String>> synonymDict = configLoader.getSynonymDict();
        List<String> expanded = new ArrayList<>();
        for (String token : tokens) {
            List<String> synonyms = synonymDict.get(token);
            if (synonyms != null && !synonyms.isEmpty()) {
                expanded.addAll(synonyms);
            }
        }
        return expanded.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 按意图选择改写策略
     *
     * <h3>策略映射</h3>
     * <table>
     *   <tr><th>意图</th><th>追加到扩展关键词</th><th>追加到改写查询</th></tr>
     *   <tr><td>FACTUAL（事实型）</td><td>同义词</td><td>关键词 + 同义词</td></tr>
     *   <tr><td>PROCEDURAL（过程型）</td><td>同义词 + 步骤/方法/教程</td><td>关键词 + 同义词 + 步骤/方法/教程/流程</td></tr>
     *   <tr><td>COMPARISON（对比型）</td><td>同义词 + 区别/对比</td><td>关键词 + 同义词 + 区别/对比/差异</td></tr>
     *   <tr><td>DEFINITIONAL（定义型）</td><td>同义词 + 定义/概念</td><td>关键词 + 同义词 + 定义/概念/含义</td></tr>
     *   <tr><td>AMBIGUOUS（模糊型）</td><td>仅原始关键词</td><td>仅原始查询（不改写）</td></tr>
     * </table>
     *
     * <h3>设计说明</h3>
     * <ul>
     *   <li><b>扩展关键词</b>：用于 BM25 精确匹配，追加意图相关的领域词提升召回率</li>
     *   <li><b>改写查询</b>：用于向量检索，追加意图相关的描述词辅助语义匹配</li>
     *   <li><b>AMBIGUOUS</b>：模糊意图不做改写，避免引入噪声误导向量检索</li>
     * </ul>
     *
     * @param intent          查询意图类型
     * @param baseQuery       基础查询文本（纠错后的文本）
     * @param keywords        L2 提取的关键词
     * @param synonymExpanded 同义词扩展列表
     * @return 改写策略（含改写后查询和扩展关键词）
     */
    private RewriteStrategy selectStrategy(QueryIntent intent, String baseQuery,
                                           List<String> keywords, List<String> synonymExpanded) {
        String rewrittenQuery;
        List<String> expandKeywords = new ArrayList<>(keywords);

        switch (intent) {
            case FACTUAL:
                // 向量查询：原始关键词 + 核心同义词
                // BM25 扩展词：全部关键词 + 全部同义词
                expandKeywords.addAll(synonymExpanded);
                rewrittenQuery = buildRewrittenQuery(baseQuery, keywords, synonymExpanded);
                break;

            case PROCEDURAL:
                // 追加 "步骤 方法 教程"
                List<String> proceduralExtras = List.of("步骤", "方法", "教程");
                expandKeywords.addAll(synonymExpanded);
                expandKeywords.addAll(proceduralExtras);
                List<String> proceduralAll = List.of("步骤", "方法", "教程", "流程");
                rewrittenQuery = buildRewrittenQuery(baseQuery, keywords, synonymExpanded, proceduralAll);
                break;

            case COMPARISON:
                // 追加 "区别 对比"
                List<String> comparisonExtras = List.of("区别", "对比");
                expandKeywords.addAll(synonymExpanded);
                expandKeywords.addAll(comparisonExtras);
                List<String> comparisonAll = List.of("区别", "对比", "差异");
                rewrittenQuery = buildRewrittenQuery(baseQuery, keywords, synonymExpanded, comparisonAll);
                break;

            case DEFINITIONAL:
                // 追加 "定义 概念"
                List<String> defExtras = List.of("定义", "概念");
                expandKeywords.addAll(synonymExpanded);
                expandKeywords.addAll(defExtras);
                List<String> defAll = List.of("定义", "概念", "含义");
                rewrittenQuery = buildRewrittenQuery(baseQuery, keywords, synonymExpanded, defAll);
                break;

            case AMBIGUOUS:
            default:
                // 原始查询不改写，仅关键词作为 BM25 扩展词
                rewrittenQuery = baseQuery;
                break;
        }

        expandKeywords = expandKeywords.stream().distinct().collect(Collectors.toList());
        return new RewriteStrategy(rewrittenQuery, expandKeywords);
    }

    /**
     * 构建改写后的查询文本（完整版）
     *
     * <h3>拼接规则</h3>
     * 以基础查询为起点，依次追加：
     * <ol>
     *   <li>基础查询（baseQuery）作为第一段</li>
     *   <li>不在 baseQuery 中的关键词</li>
     *   <li>不在 baseQuery 和已追加内容中的同义词</li>
     *   <li>不在 baseQuery 和已追加内容中的意图追加词</li>
     * </ol>
     * 所有段落用空格拼接，形成最终的改写查询文本。
     *
     * <h3>注意事项</h3>
     * 使用 {@link String#contains(CharSequence)} 做子串匹配判断是否已存在，
     * 可能导致部分词被误判为"已存在"而跳过（如"数据"作为子串在"数据库"中匹配成功）。
     * 建议改用分词后精确匹配，但当前场景下此问题影响较小
     * （关键词通常不是其他词的子串）。
     *
     * @param baseQuery    基础查询文本
     * @param keywords     关键词列表
     * @param synonyms     同义词列表
     * @param intentExtras 意图追加词列表（可为 null）
     * @return 空格拼接的改写查询文本
     */
    private String buildRewrittenQuery(String baseQuery, List<String> keywords,
                                       List<String> synonyms, List<String> intentExtras) {
        List<String> allParts = new ArrayList<>();
        allParts.add(baseQuery);
        for (String kw : keywords) {
            if (!baseQuery.contains(kw)) {
                allParts.add(kw);
            }
        }
        for (String syn : synonyms) {
            if (!baseQuery.contains(syn) && !allParts.contains(syn)) {
                allParts.add(syn);
            }
        }
        if (intentExtras != null) {
            for (String extra : intentExtras) {
                if (!baseQuery.contains(extra) && !allParts.contains(extra)) {
                    allParts.add(extra);
                }
            }
        }
        return String.join(" ", allParts);
    }

    /**
     * 构建改写后的查询文本（简化版：无意图追加词）
     *
     * @see #buildRewrittenQuery(String, List, List, List)
     */
    private String buildRewrittenQuery(String baseQuery, List<String> keywords, List<String> synonyms) {
        return buildRewrittenQuery(baseQuery, keywords, synonyms, null);
    }

    /**
     * L2 置信度计算（四维加权模型）
     *
     * <h3>维度与权重</h3>
     * <table>
     *   <tr><th>维度</th><th>权重</th><th>计算规则</th></tr>
     *   <tr><td>关键词数量</td><td>0.3</td><td>≥3个→1.0，2个→0.6，1个→0.3，0个→0.0</td></tr>
     *   <tr><td>意图明确度</td><td>0.3</td><td>AMBIGUOUS→0.0，其他→1.0</td></tr>
     *   <tr><td>同义词命中率</td><td>0.2</td><td>命中的关键词数 / 关键词总数</td></tr>
     *   <tr><td>分词质量</td><td>0.2</td><td>有效词（长度≥2且非纯数字）数 / token 总数</td></tr>
     * </table>
     *
     * <h3>设计说明</h3>
     * <ul>
     *   <li>关键词数量和意图明确度各占 0.3，是最重要的两个维度</li>
     *   <li>同义词命中率反映词典覆盖度，分词质量反映输入有效性</li>
     *   <li>最终结果保留两位小数（乘以 100 后取整再除以 100）</li>
     * </ul>
     *
     * <h3>性能考量</h3>
     * 同义词命中率计算中，对每个关键词遍历所有同义词 values 做匹配，
     * 复杂度为 O(k × v)，其中 k 为关键词数，v 为同义词总数。
     * 在词典规模较大时可能成为瓶颈，可考虑预构建反向索引优化。
     *
     * @param keywords 关键词列表
     * @param intent   查询意图
     * @param tokens   分词 token 列表
     * @return 置信度（0.0 ~ 1.0）
     */
    private double calculateL2Confidence(List<String> keywords, QueryIntent intent, List<String> tokens) {
        // 1. 关键词数量得分 (权重 0.3)
        double keywordScore;
        if (keywords == null || keywords.isEmpty()) {
            keywordScore = 0.0;
        } else if (keywords.size() >= 3) {
            keywordScore = 1.0;
        } else if (keywords.size() == 2) {
            keywordScore = 0.6;
        } else {
            keywordScore = 0.3;
        }

        // 2. 意图明确度得分 (权重 0.3)
        double intentScore;
        if (intent == QueryIntent.AMBIGUOUS) {
            intentScore = 0.0;
        } else {
            intentScore = 1.0;
        }

        // 3. 同义词命中数得分 (权重 0.2)
        double synonymScore = 0.0;
        if (keywords != null && !keywords.isEmpty()) {
            Map<String, List<String>> synonymDict = configLoader.getSynonymDict();
            // 预构建同义词 values 集合，避免对每个关键词遍历所有 values（O(k×v) → O(k)）
            Set<String> allSynonymValues = new HashSet<>();
            for (List<String> vals : synonymDict.values()) {
                if (vals != null) {
                    allSynonymValues.addAll(vals);
                }
            }
            long hitCount = keywords.stream()
                    .filter(kw -> synonymDict.containsKey(kw) || allSynonymValues.contains(kw))
                    .count();
            synonymScore = (double) hitCount / keywords.size();
        }

        // 4. 分词质量得分 (权重 0.2)
        double tokenQualityScore = 0.0;
        if (tokens != null && !tokens.isEmpty()) {
            long validCount = tokens.stream()
                    .filter(t -> t.length() >= 2 && !t.matches("\\d+"))
                    .count();
            tokenQualityScore = (double) validCount / tokens.size();
        }

        // 加权求和
        double confidence = keywordScore * 0.3 + intentScore * 0.3 + synonymScore * 0.2 + tokenQualityScore * 0.2;

        return Math.round(confidence * 100) / 100.0;
    }

    // ==================== 改写策略内部类 ====================

    /**
     * 改写策略：封装意图选择后的改写结果
     *
     * <p>由 {@link #selectStrategy(QueryIntent, String, List, List)} 方法创建，
     * 包含改写后的查询文本（用于向量检索）和扩展关键词列表（用于 BM25 精确匹配）。</p>
     *
     * @param rewrittenQuery 改写后的查询文本
     * @param expandKeywords 扩展关键词列表（去重后）
     */
    private static class RewriteStrategy {
        final String rewrittenQuery;
        final List<String> expandKeywords;

        RewriteStrategy(String rewrittenQuery, List<String> expandKeywords) {
            this.rewrittenQuery = rewrittenQuery;
            this.expandKeywords = expandKeywords;
        }
    }

    // ==================== 关键词提取 ====================

    /**
     * 简单关键词提取：基于标点符号分割
     *
     * <p>先将标点符号替换为空格，按空白字符分割，过滤单字和停用词，
     * 取前 5 个作为关键词。</p>
     *
     * <p>注意：此方法为简化版关键词提取，仅用于 L1 规则改写阶段。
     * L2 阶段使用 {@link ChineseTokenizerService#extractKeywords(String, int)} 获取更精确的结果。</p>
     *
     * @param text 待提取的文本
     * @return 最多 5 个关键词
     */
    private List<String> extractSimpleKeywords(String text) {
        String cleaned = PUNCTUATION_PATTERN.matcher(text).replaceAll(" ");
        String[] parts = cleaned.split("\\s+");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            p = p.trim();
            if (p.length() >= 2 && !configLoader.isStopWord(p)) {
                result.add(p);
            }
        }
        return result.stream().limit(5).toList();
    }

    /**
     * 提取排除关键词
     *
     * <h3>匹配模式</h3>
     * 使用 {@link #EXCLUDE_PATTERN} 正则匹配"不包含/除了/不要/排除/不含"后的内容。
     * 匹配到后按分隔符（顿号、逗号、"和"、"&"、空格）分割，再按助词
     * （"的"、"了吗"、"呢"、"吧"等）二次分割，取每个片段的首个有效词。
     *
     * <h3>示例</h3>
     * <ul>
     *   <li>"不包含糖的饮料" → 提取 "糖"（"的"后的"饮料"被过滤）</li>
     *   <li>"除了苹果、香蕉和橘子" → 提取 "苹果", "香蕉", "橘子"</li>
     *   <li>"不要Java和Python" → 提取 "Java", "Python"</li>
     * </ul>
     *
     * @param text 查询文本
     * @return 排除关键词列表
     */
    private List<String> extractExcludeKeywords(String text) {
        List<String> excludeKeywords = new ArrayList<>();
        Matcher matcher = EXCLUDE_PATTERN.matcher(text);
        while (matcher.find()) {
            String group = matcher.group(1);
            String[] parts = group.split("[、，,和&\\s]+");
            for (String part : parts) {
                // 按助词分割，取第一个片段作为排除词（如 "糖的饮料" → "糖"）
                String[] subParts = EXCLUDE_PARTICLE_SPLIT.split(part.trim());
                for (String subPart : subParts) {
                    String trimmed = subPart.trim();
                    if (!trimmed.isEmpty() && trimmed.length() >= 2) {
                        excludeKeywords.add(trimmed);
                    }
                }
            }
        }
        return excludeKeywords;
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