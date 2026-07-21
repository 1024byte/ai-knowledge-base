package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.IntentResult;
import com.hai.aiknowledgebase.interfaces.IntentClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图识别编排器
 *
 * <h2>职责</h2>
 * 按优先级依次尝试多个 {@link IntentClassifier} 实现，
 * 返回首个高置信度结果。这是"责任链 + 策略"的混合模式。
 *
 * <h2>执行流程</h2>
 * <pre>
 * 用户查询
 *   ├─ 1. RuleIntentClassifier（规则引擎，毫秒级，@Order(1)）
 *   │   └─ 置信度 ≥ 0.70 → 直接返回（快路径命中）
 *   ├─ 2. LLMIntentClassifier（LLM 分类，秒级，@Order(2)）
 *   │   └─ 成功 → 返回 LLM 结果（含改写提示）
 *   └─ 3. 返回兜底结果（AMBIGUOUS）
 * </pre>
 *
 * <h2>置信度阈值</h2>
 * <table>
 *   <tr><th>阈值</th><th>含义</th></tr>
 *   <tr><td>≥ 0.70</td><td>高置信度，直接返回，不再尝试后续策略</td></tr>
 *   <tr><td>&lt; 0.70</td><td>低置信度，继续尝试下一个策略</td></tr>
 * </table>
 *
 * <h2>异常隔离</h2>
 * 单个分类器的异常不会影响其他分类器的执行，
 * 确保编排器始终能返回结果（即使所有分类器都失败）。
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * @Service
 * public class QueryRewriteService {
 *     private final IntentRecognitionOrchestrator orchestrator;
 *
 *     public void rewrite(String query) {
 *         IntentResult result = orchestrator.recognize(query);
 *         // 使用 result.primaryIntent() 选择改写策略
 *         // 使用 result.rewriteHints() 获取改写提示词
 *     }
 * }
 * }</pre>
 *
 * @see IntentClassifier 策略接口
 * @see RuleIntentClassifier 规则引擎（快路径）
 * @see LLMIntentClassifier LLM 分类器（慢路径）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRecognitionOrchestrator {

    /**
     * 高置信度阈值：达到此阈值直接返回，不再尝试后续策略
     */
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.70;

    /**
     * Spring 自动注入所有 IntentClassifier 实现
     * <p>
     * 实现类通过 {@code @Order} 注解控制优先级：
     * {@code RuleIntentClassifier @Order(1)} → {@code LLMIntentClassifier @Order(2)}
     */
    private final List<IntentClassifier> classifiers;

    /**
     * 识别查询意图（核心方法）
     *
     * <h3>执行逻辑</h3>
     * <ol>
     *   <li>空值检查：null 或空白字符串直接返回 AMBIGUOUS</li>
     *   <li>遍历策略链：按 @Order 顺序依次尝试</li>
     *   <li>高置信度短路：置信度 ≥ 0.70 直接返回</li>
     *   <li>低置信度继续：置信度 < 0.70 继续尝试下一个策略</li>
     *   <li>兜底：所有策略都失败/低置信度，返回 AMBIGUOUS</li>
     * </ol>
     *
     * @param query 用户原始查询文本，可能为 null 或空字符串
     * @return 意图识别结果，保证非 null
     */
    public IntentResult recognize(String query) {
        if (query == null || query.isBlank()) {
            log.debug("意图识别: 空查询 → AMBIGUOUS");
            return IntentResult.ambiguous();
        }

        String trimmed = query.trim();
        IntentResult fallback = null;

        for (IntentClassifier classifier : classifiers) {
            if (!classifier.canHandle(trimmed)) {
                log.debug("意图识别: {} 跳过（canHandle=false）", classifier.getClass().getSimpleName());
                continue;
            }

            try {
                IntentResult result = classifier.classify(trimmed);

                if (result.confidence() >= HIGH_CONFIDENCE_THRESHOLD) {
                    log.info("意图识别: {} → {} (置信度: {}, 策略: {})",
                            trimmed, result.primaryIntent(), result.confidence(),
                            classifier.getClass().getSimpleName());
                    return result;
                }

                // 保留第一个结果作为兜底
                if (fallback == null) {
                    fallback = result;
                }
                log.debug("意图识别: {} 低置信度 ({}), 继续尝试下一个策略",
                        classifier.getClass().getSimpleName(), result.confidence());

            } catch (Exception e) {
                log.warn("意图识别: {} 执行失败: {}", classifier.getClass().getSimpleName(), e.getMessage());
            }
        }

        // 兜底：返回第一个低置信度结果，或 AMBIGUOUS
        if (fallback != null) {
            log.info("意图识别: {} → {} (置信度: {}, 兜底)", trimmed, fallback.primaryIntent(), fallback.confidence());
            return fallback;
        }

        log.warn("意图识别: 所有策略均失败，返回 AMBIGUOUS");
        return IntentResult.ambiguous();
    }
}
