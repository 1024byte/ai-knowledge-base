package com.hai.aiknowledgebase.dto;

import java.util.Collections;
import java.util.List;

/**
 * 意图识别结果
 *
 * <h2>设计说明</h2>
 * 相比直接返回 {@link QueryIntent} 枚举，本 Record 额外提供：
 * <ul>
 *   <li><b>置信度</b>：0.0~1.0，用于下游判断是否信任该结果</li>
 *   <li><b>备选意图</b>：当主意图置信度不足时，下游可参考备选</li>
 *   <li><b>改写提示</b>：来自 LLM 的改写建议词，直接注入查询改写流程</li>
 * </ul>
 *
 * <h2>不可变性</h2>
 * 使用 Java Record 保证线程安全和不可变性。
 *
 * @see QueryIntent 意图枚举
 * @see IntentCandidate 备选意图候选
 */
public record IntentResult(
        /** 主意图，保证非 null */
        QueryIntent primaryIntent,

        /** 置信度，范围 0.0 ~ 1.0 */
        double confidence,

        /** 备选意图列表（按概率降序），可能为空列表 */
        List<IntentCandidate> alternatives,

        /** LLM 建议的改写提示词，用于增强查询改写效果，可能为空列表 */
        List<String> rewriteHints
) {

    /**
     * 备选意图候选
     */
    public record IntentCandidate(
            /** 候选意图类型 */
            QueryIntent intent,
            /** 概率，范围 0.0 ~ 1.0 */
            double probability
    ) {}

    /**
     * 创建表示"模糊/无法识别"的结果
     */
    public static IntentResult ambiguous() {
        return new IntentResult(
                QueryIntent.AMBIGUOUS,
                0.0,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    /**
     * 创建仅包含主意图的简单结果（规则引擎使用）
     *
     * @param intent     主意图
     * @param confidence 置信度
     */
    public static IntentResult of(QueryIntent intent, double confidence) {
        return new IntentResult(
                intent,
                confidence,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    /**
     * 创建带改写提示的完整结果（LLM 使用）
     *
     * @param intent        主意图
     * @param confidence    置信度
     * @param alternatives  备选意图
     * @param rewriteHints  改写提示
     */
    public static IntentResult of(QueryIntent intent, double confidence,
                                  List<IntentCandidate> alternatives,
                                  List<String> rewriteHints) {
        return new IntentResult(
                intent,
                confidence,
                alternatives != null ? List.copyOf(alternatives) : Collections.emptyList(),
                rewriteHints != null ? List.copyOf(rewriteHints) : Collections.emptyList()
        );
    }
}
