package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.QueryIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 查询意图分类器
 * <p>
 * 基于规则 + 关键词的轻量级意图识别（不依赖 LLM），
 * 用于指导 L2 改写策略和置信度计算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryIntentClassifier {

    private final ChineseTokenizerService tokenizerService;

    // ==================== 意图关键词 ====================

    private static final Set<String> FACTUAL_KEYWORDS = Set.of(
            "多少", "几个", "是否", "能不能", "可以", "有多少", "几", "多久", "什么时候"
    );

    private static final Set<String> PROCEDURAL_KEYWORDS = Set.of(
            "如何", "怎么", "怎样", "步骤", "方法", "操作", "教程", "怎么做", "如何做", "怎么办"
    );

    private static final Set<String> COMPARISON_KEYWORDS = Set.of(
            "区别", "对比", "不同", "差异", "vs", "VS", "和", "与", "相比", "比较"
    );

    private static final Set<String> DEFINITIONAL_KEYWORDS = Set.of(
            "什么是", "定义", "含义", "意思", "是什么", "指的是", "概念", "解释"
    );

    private static final Set<String> AMBIGUOUS_PRONOUNS = Set.of(
            "那个", "这个", "它", "这", "那", "东西", "玩意"
    );

    private static final Pattern ZH_SEQ_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    /**
     * 对查询进行意图分类
     *
     * @param query 用户查询
     * @return 意图分类结果
     */
    public QueryIntent classify(String query) {
        if (query == null || query.isBlank()) {
            return QueryIntent.AMBIGUOUS;
        }

        String trimmed = query.trim();

        // 1. 检查 DEFINITIONAL（优先级最高，"什么是" 等模式明确）
        for (String keyword : DEFINITIONAL_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                log.debug("意图识别: DEFINITIONAL (匹配关键词: {})", keyword);
                return QueryIntent.DEFINITIONAL;
            }
        }

        // 2. 检查 PROCEDURAL
        for (String keyword : PROCEDURAL_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                log.debug("意图识别: PROCEDURAL (匹配关键词: {})", keyword);
                return QueryIntent.PROCEDURAL;
            }
        }

        // 3. 检查 COMPARISON（"和"/"与" 需要连接两个名词才算）
        for (String keyword : COMPARISON_KEYWORDS) {
            if ("和".equals(keyword) || "与".equals(keyword)) {
                // "和"/"与" 需要前后都有名词才判定为对比意图
                if (isComparisonConjunction(trimmed, keyword)) {
                    log.debug("意图识别: COMPARISON (匹配连接词: {})", keyword);
                    return QueryIntent.COMPARISON;
                }
            } else {
                if (trimmed.contains(keyword)) {
                    log.debug("意图识别: COMPARISON (匹配关键词: {})", keyword);
                    return QueryIntent.COMPARISON;
                }
            }
        }

        // 4. 检查 FACTUAL
        for (String keyword : FACTUAL_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                log.debug("意图识别: FACTUAL (匹配关键词: {})", keyword);
                return QueryIntent.FACTUAL;
            }
        }

        // 5. 检查 AMBIGUOUS（代词占比高或关键词过少）
        if (isAmbiguous(trimmed)) {
            log.debug("意图识别: AMBIGUOUS (代词占比高或关键词过少)");
            return QueryIntent.AMBIGUOUS;
        }

        // 6. 默认返回 FACTUAL（有明确关键词但无特殊意图标记）
        log.debug("意图识别: FACTUAL (默认)");
        return QueryIntent.FACTUAL;
    }

    /**
     * 判断 "和"/"与" 是否作为对比连接词使用
     * <p>
     * 简单规则：连接词前后都有至少2个中文字符
     */
    private boolean isComparisonConjunction(String query, String conjunction) {
        int idx = query.indexOf(conjunction);
        if (idx < 0) return false;

        // 检查连接词前是否有中文字符
        String before = query.substring(0, idx);
        // 检查连接词后是否有中文字符
        String after = query.substring(idx + conjunction.length());

        boolean hasNounBefore = ZH_SEQ_PATTERN.matcher(before).find() || before.matches(".*[a-zA-Z0-9].*");
        boolean hasNounAfter = ZH_SEQ_PATTERN.matcher(after).find() || after.matches(".*[a-zA-Z0-9].*");

        return hasNounBefore && hasNounAfter;
    }

    /**
     * 判断查询是否为模糊查询
     * <p>
     * 条件：代词占比高 或 有效关键词过少
     */
    private boolean isAmbiguous(String query) {
        // 代词占比检查
        int pronounCount = 0;
        for (String pronoun : AMBIGUOUS_PRONOUNS) {
            if (query.contains(pronoun)) {
                pronounCount++;
            }
        }
        if (pronounCount >= 1) {
            // 检查代词在查询中的占比
            long pronounChars = AMBIGUOUS_PRONOUNS.stream()
                    .filter(query::contains)
                    .mapToLong(String::length)
                    .sum();
            if (query.length() > 0 && (double) pronounChars / query.length() > 0.3) {
                return true;
            }
        }

        // 关键词过少检查
        List<String> tokens = tokenizerService.tokenize(query);
        if (tokens.size() <= 1) {
            return true;
        }

        return false;
    }
}
