package com.hai.aiknowledgebase.queryrewrite.corrector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 三层漏斗纠错编排器
 *
 * <h2>漏斗模型</h2>
 * <pre>
 * 输入查询
 *   ├─ L1: word-checker  (50ms,  置信度 ≥ 0.85 → 返回)
 *   ├─ L2: 拼音匹配      (200ms, 置信度 ≥ 0.80 → 返回)
 *   └─ L3: LLM 语义      (2000ms, 兜底)
 * </pre>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>每层独立超时，超时自动降级到下一层，不阻塞主流程</li>
 *   <li>L1 最快，覆盖绝大部分简单错别字</li>
 *   <li>L2 处理同音字错别字（中文场景高频）</li>
 *   <li>L3 语义兜底，处理复杂错别字</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FunnelQueryCorrector {

    private final L1WordCheckerCorrector l1;
    private final L2PinyinCorrector l2;
    private final L3LLMCorrector l3;

    @Value("${corrector.l1.threshold:0.85}")
    private double l1Threshold;

    @Value("${corrector.l1.timeout-ms:50}")
    private long l1TimeoutMs;

    @Value("${corrector.l2.threshold:0.80}")
    private double l2Threshold;

    @Value("${corrector.l2.timeout-ms:200}")
    private long l2TimeoutMs;

    @Value("${corrector.l3.timeout-ms:2000}")
    private long l3TimeoutMs;

    /**
     * 执行三层漏斗纠错
     *
     * @param query 原始查询文本
     * @return 纠错后的查询文本（如果无需纠错或全部失败，返回原始查询）
     */
    public String correct(String query) {
        if (query == null || query.isBlank()) {
            return query != null ? query : "";
        }

        // L1: word-checker 词典 + 编辑距离
        CorrectionResult r1 = executeWithTimeout(l1, query, l1TimeoutMs);
        if (r1 != null && r1.getConfidence() >= l1Threshold) {
            log.info("L1 纠错命中 | {} → {} | 置信度: {}",
                    query, r1.getCorrectedQuery(), r1.getConfidence());
            return r1.getCorrectedQuery();
        }
        boolean l1FoundError = r1 != null && r1.isCorrected();

        // L2: 拼音匹配
        CorrectionResult r2 = executeWithTimeout(l2, query, l2TimeoutMs);
        if (r2 != null && r2.getConfidence() >= l2Threshold) {
            log.info("L2 纠错命中 | {} → {} | 置信度: {}",
                    query, r2.getCorrectedQuery(), r2.getConfidence());
            return r2.getCorrectedQuery();
        }

        // L1 和 L2 都没发现错误 → 短路返回，跳过 L3
        boolean l2FoundError = r2 != null && r2.isCorrected();
        if (!l1FoundError && !l2FoundError) {
            log.debug("L1/L2 均未发现错误，跳过 L3，返回原始查询");
            return query;
        }

        // L3: LLM 兜底（仅当 L1 或 L2 发现了疑似错误时才调用）
        CorrectionResult r3 = executeWithTimeout(l3, query, l3TimeoutMs);
        if (r3 != null) {
            if (r3.isCorrected()) {
                log.info("L3 纠错命中 | {} → {} | 置信度: {}",
                        query, r3.getCorrectedQuery(), r3.getConfidence());
            }
            return r3.getCorrectedQuery();
        }

        log.warn("所有纠错层均超时或失败，返回原始查询: {}", query);
        return query;
    }

    /**
     * 带超时的纠错执行
     *
     * <p>超时后调用 {@link CompletableFuture#cancel(boolean)} 取消后台任务，
     * 避免已超时的纠错任务继续占用线程和 CPU 资源。</p>
     */
    private CorrectionResult executeWithTimeout(CorrectorLayer layer, String query, long timeoutMs) {
        CompletableFuture<CorrectionResult> future = CompletableFuture
                .supplyAsync(() -> layer.correct(query));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("{} 纠错超时 ({}ms)，已取消后台任务", layer.getLayerName(), timeoutMs);
        } catch (Exception e) {
            log.error("{} 纠错异常: {}", layer.getLayerName(), e.getMessage());
        }
        return null;
    }
}