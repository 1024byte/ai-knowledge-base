package com.hai.aiknowledgebase.common;

import dev.langchain4j.model.TokenCountEstimator;

/**
 * <h2>文本 token 工具类</h2>
 *
 * <p>提供按 token 数量对文本进行截取的通用方法，供各类切片器复用，避免重复实现。</p>
 */
public final class TextTokenUtils {

    private TextTokenUtils() {
    }

    /**
     * <h3>从文本尾部硬截取不超过 maxTokens 个 token 的子串</h3>
     *
     * <p>用二分法定位最长的尾部子串，使其 token 数不超过 maxTokens，
     * 避免整段（如无空行的目录列表）被整段塞入导致重叠失控。</p>
     *
     * <p><b>注意</b>：若 maxTokens &le; 0，返回空串。若文本本身不超过 maxTokens，
     * 原样返回（不 strip，保持调用方上下文）。</p>
     *
     * @param text          原始文本
     * @param maxTokens     目标 token 上限
     * @param tokenEstimator token 估算器（与切片器共用，保证口径一致）
     * @return 从尾部截取、token 数不超过 maxTokens 的子串
     */
    public static String truncateTailByTokens(String text, int maxTokens,
                                              TokenCountEstimator tokenEstimator) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        if (tokenEstimator.estimateTokenCountInText(text) <= maxTokens) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (tokenEstimator.estimateTokenCountInText(text.substring(mid)) <= maxTokens) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return text.substring(low).strip();
    }
}