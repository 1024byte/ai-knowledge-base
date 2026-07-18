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

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService{

    private final QueryRewriteConfigLoader configLoader;
    private final ChineseTokenizerService tokenizerService;
    private final QueryIntentClassifier intentClassifier;
    private final QueryCorrector queryCorrector;

    @Value("${query-rewrite.l1.confidence-threshold:0.85}")
    private double l1ConfidenceThreshold;

    @Value("${query-rewrite.l2.confidence-threshold:0.70}")
    private double l2ConfidenceThreshold;

    @Value("${query-rewrite.enabled:true}")
    private boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LocalQueryRewriter llmRewriter;

    // ==================== 预编译正则常量 ====================

    private static final Pattern ZH_SEQ_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");
    private static final Pattern EN_WORD_PATTERN = Pattern.compile("[a-zA-Z0-9_]+");
    private static final Pattern EXCLUDE_PATTERN = Pattern.compile(
            "(?:不包含|除了|不要|排除|不含)[\\s]*([\\u4e00-\\u9fa5a-zA-Z0-9]+(?:[、，,和&\\s]+[\\u4e00-\\u9fa5a-zA-Z0-9]+)*)"
    );
    /** 排除词提取后，用于按助词等非关键词分割的正则 */
    private static final Pattern EXCLUDE_PARTICLE_SPLIT = Pattern.compile("[的了吗呢吧啊呀嘛]+");
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[，,、。.！!？?；;：:\"\u201C\u201D\"\"()（）]");

    // ==================== 主入口 ====================

    public QueryRewriteResult rewrite(String query) {
        RewriteRequest request = RewriteRequest.builder()
                .query(query)
                .routingDecision(RoutingDecision.RULE_ONLY)
                .build();
        return rewrite(request);
    }

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

    // ==================== L1：安全替换器（修复区间检测） ====================

    /**
     * L1 规则改写
     *
     * <p>核心修复：固定映射和同义词统一抽象为候选列表，按长度降序排序，</p>
     * <p>确保长词优先匹配，彻底解决短词误伤长词的问题。</p>
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

// ==================== 辅助方法 ====================

    /**
     * 检查区间是否与任何已占用区间重叠
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
     * 获取命中的固定映射中最高的 confidence
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
     * 区间类：记录已占用的 [start, end)
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
     * 替换片段
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
     * 候选替换项（统一抽象）
     *
     * <p>将固定映射和同义词统一为候选列表，便于统一排序和匹配</p>
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

    private QueryRewriteResult applyNlpRewrite(String originalQuery, QueryRewriteResult l1Result) {
        // 1. 查询纠错
        String correctedQuery = queryCorrector.correct(l1Result.getRewrittenQuery());

        // 2. 中文分词
        List<String> tokens = tokenizerService.tokenize(correctedQuery, true);

        // 3. 意图识别
        QueryIntent intent = intentClassifier.classify(correctedQuery);
        log.debug("L2 意图识别结果: {} | 分词: {}", intent, tokens);

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
     * 构建改写后的查询文本
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

    private String buildRewrittenQuery(String baseQuery, List<String> keywords, List<String> synonyms) {
        return buildRewrittenQuery(baseQuery, keywords, synonyms, null);
    }

    /**
     * L2 置信度计算（四维加权）
     *
     * <p>因素与权重：</p>
     * <ul>
     *   <li>关键词数量 (0.3)：≥3 个关键词得满分</li>
     *   <li>意图明确度 (0.3)：FACTUAL/PROCEDURAL/COMPARISON/DEFINITIONAL 得满分，AMBIGUOUS 得 0 分</li>
     *   <li>同义词命中数 (0.2)：命中同义词词典的关键词占比</li>
     *   <li>分词质量 (0.2)：有效词（非停用词、非单字）占比</li>
     * </ul>
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
            long hitCount = keywords.stream()
                    .filter(kw -> synonymDict.containsKey(kw) || synonymDict.values().stream()
                            .anyMatch(vals -> vals != null && vals.contains(kw)))
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

    private static class RewriteStrategy {
        final String rewrittenQuery;
        final List<String> expandKeywords;

        RewriteStrategy(String rewrittenQuery, List<String> expandKeywords) {
            this.rewrittenQuery = rewrittenQuery;
            this.expandKeywords = expandKeywords;
        }
    }

    // ==================== 关键词提取 ====================

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

    public boolean hasL3Rewriter() {
        return llmRewriter != null;
    }
}