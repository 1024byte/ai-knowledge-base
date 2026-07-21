package com.hai.aiknowledgebase.interfaces;

import com.hai.aiknowledgebase.dto.IntentResult;

/**
 * 意图分类器统一抽象（策略接口）
 *
 * <h2>设计理念</h2>
 * 所有意图分类策略（规则引擎、LLM、混合等）都实现此接口。
 * 通过 {@link #canHandle(String)} 判断是否处理该查询，
 * 通过 {@link #classify(String)} 执行分类。
 *
 * <h2>策略链模式</h2>
 * 多个实现类按优先级组成责任链，由 {@code IntentRecognitionOrchestrator}
 * 依次尝试，首个返回高置信度结果的策略胜出。
 *
 * <h2>与 {@link com.hai.aiknowledgebase.service.QueryIntentClassifier} 的关系</h2>
 * 本接口是意图识别模块的新抽象层。旧的 {@code QueryIntentClassifier}
 * 被标记为 @Deprecated，其逻辑迁移至 {@code RuleIntentClassifier}。
 *
 * @see com.hai.aiknowledgebase.service.RuleIntentClassifier 规则引擎实现
 * @see com.hai.aiknowledgebase.service.LLMIntentClassifier LLM 实现
 * @see com.hai.aiknowledgebase.service.IntentRecognitionOrchestrator 编排器
 */

public interface IntentClassifier {

    /**
     * 判断此分类器是否能处理该查询
     *
     * <h3>典型实现</h3>
     * <ul>
     *   <li>规则引擎：对非空查询总是返回 true（作为兜底策略）</li>
     *   <li>LLM 分类器：总是返回 true（作为慢路径兜底），或仅在规则引擎低置信度时返回 true</li>
     * </ul>
     *
     * @param query 用户查询文本（已去除首尾空白，保证非空）
     * @return true 表示此分类器可以处理该查询
     */
    boolean canHandle(String query);

    /**
     * 执行意图分类
     *
     * <h3>契约</h3>
     * <ul>
     *   <li>调用前应确保 {@link #canHandle(String)} 返回 true</li>
     *   <li>返回的 {@link IntentResult} 保证非 null</li>
     *   <li>置信度范围 0.0 ~ 1.0</li>
     * </ul>
     *
     * @param query 用户查询文本（已去除首尾空白，保证非空）
     * @return 意图识别结果，保证非 null
     * @throws com.hai.aiknowledgebase.exception.BusinessException 分类失败时抛出
     */
    IntentResult classify(String query);
}
