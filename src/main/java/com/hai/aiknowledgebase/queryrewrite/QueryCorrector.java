package com.hai.aiknowledgebase.queryrewrite;

import com.hai.aiknowledgebase.queryrewrite.corrector.FunnelQueryCorrector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 查询纠错服务（Query Corrector）
 *
 * <h2>功能概述</h2>
 * 采用三层漏斗模型进行查询纠错，由 {@link FunnelQueryCorrector} 编排：
 * <ol>
 *   <li><b>L1: word-checker</b> — 词典 + 编辑距离，50ms 超时，置信度阈值 0.85</li>
 *   <li><b>L2: 拼音匹配</b> — 同音字匹配 + 编辑距离，200ms 超时，置信度阈值 0.80</li>
 *   <li><b>L3: LLM 语义</b> — 语义理解兜底，2000ms 超时</li>
 * </ol>
 * 每层超时自动降级，不阻塞主流程。全部失败时返回原始查询。
 *
 * <h2>使用场景</h2>
 * 在 {@link QueryRewriteService} 的 L2 NLP 增强阶段被调用，
 * 纠错后的查询用于后续的意图识别和查询改写。
 *
 * <h2>设计说明</h2>
 * <ul>
 *   <li>使用 {@link ThreadLocal} 存储纠错状态，保证线程安全</li>
 *   <li>纠错逻辑委托给 {@link FunnelQueryCorrector}，本类只负责接口适配和状态追踪</li>
 * </ul>
 *
 * @see FunnelQueryCorrector 三层漏斗编排器
 * @see QueryRewriteService 查询改写服务（调用方）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryCorrector {

    private final FunnelQueryCorrector funnelQueryCorrector;

    /**
     * 线程隔离的纠错状态标记
     */
    private final ThreadLocal<Boolean> lastCorrectionHappened = new ThreadLocal<>();

    /**
     * 对查询进行纠错（委托给三层漏斗）
     *
     * @param query 原始查询文本，可能为 null 或空字符串
     * @return 纠错后的查询文本。如果未发生纠错，返回原始查询
     */
    public String correct(String query) {
        lastCorrectionHappened.set(false);

        if (query == null || query.isBlank()) {
            return query != null ? query : "";
        }

        String corrected = funnelQueryCorrector.correct(query);
        if (!corrected.equals(query)) {
            lastCorrectionHappened.set(true);
        }

        return corrected;
    }

    /**
     * 判断最近一次 correct 调用是否发生了纠错
     */
    public boolean hasCorrection() {
        Boolean result = lastCorrectionHappened.get();
        return result != null && result;
    }
}