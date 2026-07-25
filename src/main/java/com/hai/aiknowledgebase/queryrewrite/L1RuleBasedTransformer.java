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
 * L1 层改写服务
 * 规则改写
 * 1. 固定映射
 * 2. 同义词替换
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L1RuleBasedTransformer {


    /** 词典配置加载器：提供同义词词典、固定映射、停用词，支持定时热加载 */
    private final QueryRewriteConfigLoader configLoader;

    private final KeywordsUtils keywordsUtils;

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
    public QueryRewriteResult applyRuleRewrite(String query) {
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
            String firstSynonym = synonyms.get(0);//为什这里取第一个同义词
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

        // ========== 4. 排除词 ==========

        List<String> excludeKeywords = keywordsUtils.extractExcludeKeywords(query);

        // ========== 5. 置信度 ==========

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