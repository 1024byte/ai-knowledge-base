package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.IntentResult;
import com.hai.aiknowledgebase.dto.QueryIntent;
import com.hai.aiknowledgebase.interfaces.IntentClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基于规则引擎的意图分类器
 *
 * <h2>功能概述</h2>
 * 纯规则驱动的轻量级意图识别，不依赖 LLM，响应时间在毫秒级。
 * 在策略链中优先级最高（{@code @Order(1)}），作为"快路径"首先执行。
 *
 * <h2>分类体系</h2>
 * 将用户查询分为 5 种意图类型，按优先级从高到低匹配：
 * <ol>
 *   <li><b>DEFINITIONAL</b>："什么是"、"定义"等模式最为明确</li>
 *   <li><b>PROCEDURAL</b>："如何"、"怎么"等操作类关键词</li>
 *   <li><b>COMPARISON</b>："区别"、"对比"等比较类关键词</li>
 *   <li><b>FACTUAL</b>："多少"、"是否"等疑问类关键词</li>
 *   <li><b>AMBIGUOUS</b>：代词占比过高或有效关键词过少</li>
 * </ol>
 *
 * <h2>置信度计算</h2>
 * <ul>
 *   <li>DEFINITIONAL / PROCEDURAL / COMPARISON：关键词匹配明确，置信度 0.90</li>
 *   <li>FACTUAL：关键词范围较宽泛，置信度 0.80</li>
 *   <li>AMBIGUOUS：无法确定意图，置信度 0.30</li>
 *   <li>默认 FACTUAL：无特殊标记但内容明确，置信度 0.60</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * 作为 {@link IntentRecognitionOrchestrator} 策略链的第一环。
 * 高置信度结果直接返回，低置信度结果交由 LLM 分类器兜底。
 *
 * @see IntentClassifier 策略接口
 * @see LLMIntentClassifier LLM 兜底分类器
 * @see IntentRecognitionOrchestrator 编排器
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RuleIntentClassifier implements IntentClassifier {

    private final ChineseTokenizerService tokenizerService;

    // ==================== 意图关键词定义 ====================

    private static final Set<String> DEFINITIONAL_KEYWORDS = Set.of(
            "什么是", "定义", "含义", "意思", "是什么", "指的是", "概念", "解释"
    );

    private static final Set<String> PROCEDURAL_KEYWORDS = Set.of(
            "如何", "怎么", "怎样", "步骤", "方法", "操作", "教程", "怎么做", "如何做", "怎么办"
    );

    private static final Set<String> COMPARISON_KEYWORDS = Set.of(
            "区别", "对比", "不同", "差异", "vs", "VS", "和", "与", "相比", "比较"
    );

    private static final Set<String> FACTUAL_KEYWORDS = Set.of(
            "多少", "几个", "是否", "能不能", "可以", "有多少", "几", "多久", "什么时候"
    );

    private static final Set<String> AMBIGUOUS_PRONOUNS = Set.of(
            "那个", "这个", "它", "这", "那", "东西", "玩意"
    );

    private static final Pattern ZH_SEQ_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    // ==================== IntentClassifier 接口实现 ====================

    /**
     * 规则引擎对非空查询总是可以尝试处理（作为快路径）
     */
    @Override
    public boolean canHandle(String query) {
        return query != null && !query.isBlank();
    }

    /**
     * 执行规则引擎意图分类
     *
     * <h3>分类流程（按优先级顺序）</h3>
     * <ol>
     *   <li>DEFINITIONAL：检查定义类关键词，置信度 0.90</li>
     *   <li>PROCEDURAL：检查操作类关键词，置信度 0.90</li>
     *   <li>COMPARISON：检查对比类关键词（"和"/"与"需验证上下文），置信度 0.90</li>
     *   <li>FACTUAL：检查事实类疑问词，置信度 0.80</li>
     *   <li>AMBIGUOUS：代词占比高或关键词过少，置信度 0.30</li>
     *   <li>默认兜底：FACTUAL，置信度 0.60</li>
     * </ol>
     *
     * @param query 用户查询文本（已去除首尾空白，保证非空）
     * @return 意图识别结果
     */
    @Override
    public IntentResult classify(String query) {
        String trimmed = query.trim();

        // 第1步：检查 DEFINITIONAL（优先级最高）
        for (String keyword : DEFINITIONAL_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                log.debug("规则引擎命中: DEFINITIONAL (关键词: {})", keyword);
                return IntentResult.of(QueryIntent.DEFINITIONAL, 0.90);
            }
        }

        // 第2步：检查 PROCEDURAL
        for (String keyword : PROCEDURAL_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                log.debug("规则引擎命中: PROCEDURAL (关键词: {})", keyword);
                return IntentResult.of(QueryIntent.PROCEDURAL, 0.90);
            }
        }

        // 第3步：检查 COMPARISON
        for (String keyword : COMPARISON_KEYWORDS) {
            if ("和".equals(keyword) || "与".equals(keyword)) {
                if (isComparisonConjunction(trimmed, keyword)) {
                    log.debug("规则引擎命中: COMPARISON (连接词: {})", keyword);
                    return IntentResult.of(QueryIntent.COMPARISON, 0.90);
                }
            } else {
                if (trimmed.contains(keyword)) {
                    log.debug("规则引擎命中: COMPARISON (关键词: {})", keyword);
                    return IntentResult.of(QueryIntent.COMPARISON, 0.90);
                }
            }
        }

        // 第4步：检查 FACTUAL
        for (String keyword : FACTUAL_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                log.debug("规则引擎命中: FACTUAL (关键词: {})", keyword);
                return IntentResult.of(QueryIntent.FACTUAL, 0.80);
            }
        }

        // 第5步：检查 AMBIGUOUS
        if (isAmbiguous(trimmed)) {
            log.debug("规则引擎命中: AMBIGUOUS (代词占比高或关键词过少)");
            return IntentResult.of(QueryIntent.AMBIGUOUS, 0.30);
        }

        // 第6步：默认兜底为 FACTUAL
        log.debug("规则引擎命中: FACTUAL (默认兜底)");
        return IntentResult.of(QueryIntent.FACTUAL, 0.60);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 判断"和"/"与"是否为对比连接词
     * <p>
     * 需要连接词前后都存在中文字符，才判定为有效的对比结构。
     * 避免误判"我和你"等非对比用法。
     */
    private boolean isComparisonConjunction(String query, String conjunction) {
        int idx = query.indexOf(conjunction);
        if (idx <= 0 || idx + conjunction.length() >= query.length()) {
            return false;
        }
        String before = query.substring(0, idx);
        String after = query.substring(idx + conjunction.length());
        return ZH_SEQ_PATTERN.matcher(before).find()
                && ZH_SEQ_PATTERN.matcher(after).find();
    }

    /**
     * 判断查询是否属于模糊查询
     * <p>
     * 两个条件满足任一即判定为模糊：
     * <ol>
     *   <li>指示代词占比超过 30%</li>
     *   <li>分词后有效关键词数量 ≤ 1</li>
     * </ol>
     */
    private boolean isAmbiguous(String query) {
        // 条件1：代词占比
        int pronounCount = 0;
        for (String pronoun : AMBIGUOUS_PRONOUNS) {
            int fromIndex = 0;
            while ((fromIndex = query.indexOf(pronoun, fromIndex)) != -1) {
                pronounCount += pronoun.length();
                fromIndex += pronoun.length();
            }
        }
        if (query.length() > 0 && (double) pronounCount / query.length() > 0.3) {
            return true;
        }

        // 条件2：有效关键词数量
        List<String> tokens = tokenizerService.tokenize(query);
        return tokens.size() <= 1;
    }
}
