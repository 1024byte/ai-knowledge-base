package com.hai.aiknowledgebase.queryrewrite.corrector;

/**
 * 纠错层接口
 * <p>
 * 每层实现自己的纠错逻辑，由 {@link FunnelQueryCorrector} 按漏斗顺序调用。
 * 每层独立判断置信度，低置信度时自动降级到下一层。
 * </p>
 */
public interface CorrectorLayer {

    /**
     * 执行纠错
     *
     * @param query 原始查询文本
     * @return 纠错结果（包含纠错后文本和置信度）
     */
    CorrectionResult correct(String query);

    /**
     * 获取纠错层标识
     *
     * @return 层名称（L1/L2/L3）
     */
    String getLayerName();

    /**
     * 判断该层是否可用
     *
     * @return true 表示依赖就绪，可以调用
     */
    default boolean isAvailable() {
        return true;
    }
}