package com.hai.aiknowledgebase.model;

import java.util.List;

/**
 * 精排接口。
 *
 * <p>{@code RerankerService}（本地 ONNX）与 {@code RerankProvider}（按配置分发）
 * 均实现本接口；消费方注入接口类型即可支持运行时切换供应商。</p>
 */
public interface Reranker {

    /** 精排总开关（沿用 {@code reranker.enabled}）。 */
    boolean isEnabled();

    /** 当前是否有可用的精排实现（本地模型已加载，或云端已配置）。 */
    boolean isAvailable();

    /** 精排最低分数阈值。 */
    double getMinScore();

    /** 单次精排最大候选数的兜底默认值。 */
    int getMaxDocs();

    /** 单次精排超时时间的兜底默认值（毫秒）。 */
    long getTimeoutMs();

    /** 精排（使用兜底候选数与超时）。 */
    List<RerankResult> rerank(String query, List<String> docs, int topN);

    /** 精排（可覆盖候选数与超时）。 */
    List<RerankResult> rerank(String query, List<String> docs, int topN, int maxDocs, long timeoutMs);
}
