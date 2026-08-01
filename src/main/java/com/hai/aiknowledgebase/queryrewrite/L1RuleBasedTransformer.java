package com.hai.aiknowledgebase.queryrewrite;

import com.hai.aiknowledgebase.dto.QueryRewriteResult;
import com.hai.aiknowledgebase.dto.RewritePath;
import com.hai.aiknowledgebase.queryrewrite.word.Candidate;
import com.hai.aiknowledgebase.queryrewrite.word.Interval;
import com.hai.aiknowledgebase.queryrewrite.word.Replacement;
import com.hai.aiknowledgebase.service.QueryRewriteConfigLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * L1 层改写服务（规则改写）
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li><b>固定映射替换</b>：按 key 长度降序匹配，长词优先，区间占用检测防止重复替换</li>
 *   <li><b>排除关键词提取</b>：正则匹配"不包含/除了/不要"等模式</li>
 *   <li><b>置信度计算</b>：固定映射命中 → 取映射配置的置信度上限；无命中 → 0.50</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L1RuleBasedTransformer {

    private final QueryRewriteConfigLoader configLoader;
    private final KeywordsUtils keywordsUtils;

    /**
     * L1 规则改写：固定映射替换 + 排除关键词提取
     *
     * @param query 原始查询文本（已 trim）
     * @return L1 改写结果
     */
    public QueryRewriteResult applyRuleRewrite(String query) {
        Map<String, String> fixedMapping = configLoader.getFixedMapping();
        Map<String, Double> fixedConfidenceMap = configLoader.getFixedMappingConfidence();

        // ========== 1. 构建候选列表（仅固定映射） ==========

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, String> entry : fixedMapping.entrySet()) {
            String key = entry.getKey();
            candidates.add(new Candidate(
                    key,
                    entry.getValue(),
                    true,
                    fixedConfidenceMap.getOrDefault(key, 0.95)
            ));
        }

        candidates.sort((a, b) -> {
            int lenCmp = Integer.compare(b.key.length(), a.key.length());
            if (lenCmp != 0) return lenCmp;
            return 0;
        });

        // ========== 2. 匹配 + 区间占用检测 ==========

        List<Replacement> replacements = new ArrayList<>();
        List<Interval> occupiedIntervals = new ArrayList<>();
        boolean fixedMappingHit = false;

        for (Candidate candidate : candidates) {
            String key = candidate.key;
            int searchStart = 0;

            while (true) {
                int index = query.indexOf(key, searchStart);
                if (index < 0) break;
                int end = index + key.length();

                if (!isOverlapping(occupiedIntervals, index, end)) {
                    occupiedIntervals.add(new Interval(index, end));
                    replacements.add(new Replacement(index, end, candidate.replacement));
                    fixedMappingHit = true;
                    break;
                }
                searchStart = index + 1;
            }
        }

        // ========== 3. 执行替换（从后往前） ==========

        replacements.sort((a, b) -> Integer.compare(b.start, a.start));

        StringBuilder resultBuilder = new StringBuilder(query);
        for (Replacement r : replacements) {
            resultBuilder.replace(r.start, r.end, r.replacement);
        }
        String result = resultBuilder.toString();

        // ========== 4. 排除关键词提取 ==========

        List<String> excludeKeywords = keywordsUtils.extractExcludeKeywords(query);

        // ========== 5. 置信度 ==========

        double confidence = fixedMappingHit
                ? getFixedMappingMaxConfidence(query, fixedConfidenceMap)
                : 0.50;

        return QueryRewriteResult.builder()
                .rewrittenQuery(result)
                .expandKeywords(Collections.emptyList())
                .excludeKeywords(excludeKeywords)
                .confidence(confidence)
                .path(RewritePath.L1_RULE)
                .build();
    }


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

}