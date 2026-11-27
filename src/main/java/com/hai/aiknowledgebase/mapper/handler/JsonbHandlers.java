package com.hai.aiknowledgebase.mapper.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hai.aiknowledgebase.dto.RecallTestResponse;
import com.hai.aiknowledgebase.dto.eval.EvalMetricsSummary;
import com.hai.aiknowledgebase.dto.eval.EvalRunConfig;

import java.util.List;
import java.util.Map;

/**
 * 携带泛型信息的 JSONB 处理器集合，供实体 {@code @TableField(typeHandler=...)} 引用。
 */
public final class JsonbHandlers {

    private JsonbHandlers() {
    }

    /** List&lt;Long&gt;：相关文档 ID、召回文档 ID */
    public static class LongList extends JsonbTypeHandler<List<Long>> {
        public LongList() {
            super(new TypeReference<>() {
            });
        }
    }

    /** List&lt;Double&gt;：召回分数 */
    public static class DoubleList extends JsonbTypeHandler<List<Double>> {
        public DoubleList() {
            super(new TypeReference<>() {
            });
        }
    }

    /** List&lt;String&gt;：Case 标签 */
    public static class StringList extends JsonbTypeHandler<List<String>> {
        public StringList() {
            super(new TypeReference<>() {
            });
        }
    }

    /** Map&lt;String, Boolean&gt;：hit@K 标记 */
    public static class BooleanMap extends JsonbTypeHandler<Map<String, Boolean>> {
        public BooleanMap() {
            super(new TypeReference<>() {
            });
        }
    }

    /** Map&lt;String, String&gt;：参数快照 */
    public static class StringMap extends JsonbTypeHandler<Map<String, String>> {
        public StringMap() {
            super(new TypeReference<>() {
            });
        }
    }

    /** 运行配置快照 */
    public static class RunConfig extends JsonbTypeHandler<EvalRunConfig> {
        public RunConfig() {
            super(new TypeReference<>() {
            });
        }
    }

    /** 汇总指标 */
    public static class MetricsSummary extends JsonbTypeHandler<EvalMetricsSummary> {
        public MetricsSummary() {
            super(new TypeReference<>() {
            });
        }
    }

    /** 完整召回测试响应（Case 明细快照） */
    public static class RecallSnapshot extends JsonbTypeHandler<RecallTestResponse> {
        public RecallSnapshot() {
            super(new TypeReference<>() {
            });
        }
    }
}
