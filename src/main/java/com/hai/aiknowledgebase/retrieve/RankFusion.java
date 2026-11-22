package com.hai.aiknowledgebase.retrieve;

import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <h2>RRF（倒数排名融合）编排</h2>
 *
 * <p>收敛原生产链路与召回测试链路的两份 RRF 融合实现。两者公式完全相同，
 * 唯一差异是<b>去重键语义</b>，因此保留两条独立入口，不可混用：</p>
 * <ul>
 *   <li>{@link #reciprocalRankFusion}：生产链路，去重键 = 文本前 100 字符 + source</li>
 *   <li>{@link #fuseRanked}：召回测试链路，去重键 = 片段主键 {@code chunk_id}</li>
 * </ul>
 *
 * <h3>计算公式</h3>
 * <pre>
 * RRF_score(d) = Σ 1 / (k + rank_i(d))
 * </pre>
 */
@Slf4j
@Component
public class RankFusion {

    /**
     * RRF 算法的平滑参数 k。
     * 公式：score = Σ 1/(k + rank_i)
     * k 越大，排名差异对分数的影响越小，融合更平滑。
     * 默认值 60，是学术界和工业界验证的最佳实践值。
     */
    @Value("${hybrid-search.rrf.k:60}")
    private int rrfK = 60;

    /** 混合检索总开关，可通过配置文件关闭以节省资源 */
    @Value("${hybrid-search.enabled:true}")
    private boolean hybridEnabled = true;

    /**
     * 查询混合检索是否启用。
     *
     * @return true 表示启用混合检索，false 表示仅使用向量检索
     */
    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    /**
     * 获取 RRF 平滑系数的配置默认值（yml 兜底）。
     * 供上层按参数动态读取时作为 fallback 使用。
     */
    public int getDefaultRrfK() {
        return rrfK;
    }

    /**
     * <h3>RRF 融合（生产链路去重键：文本前缀 + source）</h3>
     *
     * <p>同一文档在两路中都出现时，分数累加；保留首次出现的 RankedResult
     * （通常来自向量检索），并用 RRF 总分替换单一路径分数。</p>
     *
     * @param vectorResults  向量检索结果（按相似度降序）
     * @param keywordResults 关键词检索结果（按 BM25 分数降序）
     * @param topK           最终返回结果数量上限
     * @param rrfK           RRF 平滑参数
     * @return RRF 融合排序后的结果列表
     */
    public List<RankedResult> reciprocalRankFusion(
            List<RankedResult> vectorResults,
            List<RankedResult> keywordResults,
            int topK,
            int rrfK) {

        // 使用 LinkedHashMap 保持插入顺序，用于最终结果映射
        Map<String, RankedResult> allResults = new LinkedHashMap<>();
        // key: 文档唯一标识，value: 该文档的 RRF 累加分数
        Map<String, Double> rrfScores = new HashMap<>();

        // 步骤1：计算向量检索路径的排名贡献
        for (int i = 0; i < vectorResults.size(); i++) {
            String key = generateKey(vectorResults.get(i).getSegment());
            allResults.putIfAbsent(key, vectorResults.get(i));
            rrfScores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
        }

        // 步骤2：计算关键词检索路径的排名贡献
        for (int i = 0; i < keywordResults.size(); i++) {
            String key = generateKey(keywordResults.get(i).getSegment());
            allResults.putIfAbsent(key, keywordResults.get(i));
            rrfScores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
        }

        // 步骤3：按 RRF 总分降序排序，取 Top-K
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    RankedResult original = allResults.get(entry.getKey());
                    return new RankedResult(original.getSegment(), entry.getValue(),
                            original.getVectorScore(), original.getBm25Score());
                })
                .collect(Collectors.toList());
    }

    /**
     * <h3>RRF 融合（以片段主键为去重键，供召回测试等观测场景使用）</h3>
     *
     * <p>去重键使用片段主键 {@code chunk_id}（而非文本前缀 + source），避免内容高度相似
     * 但主键不同的片段被误合并。缺少 {@code chunk_id} 的片段会被跳过，无法参与融合。</p>
     *
     * @param vectorResults  向量检索结果（按相似度降序）
     * @param keywordResults 关键词检索结果（按 BM25 分数降序）
     * @param topK           最终返回结果数量上限
     * @param rrfK           RRF 平滑参数
     * @return RRF 融合排序后的结果列表
     */
    public List<RankedResult> fuseRanked(List<RankedResult> vectorResults, List<RankedResult> keywordResults,
                                         int topK, int rrfK) {
        Map<String, RankedResult> allResults = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            String key = fusionKey(vectorResults.get(i).getSegment());
            if (key == null) {
                continue;
            }
            allResults.putIfAbsent(key, vectorResults.get(i));
            rrfScores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            String key = fusionKey(keywordResults.get(i).getSegment());
            if (key == null) {
                continue;
            }
            allResults.putIfAbsent(key, keywordResults.get(i));
            rrfScores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    RankedResult original = allResults.get(entry.getKey());
                    return new RankedResult(original.getSegment(), entry.getValue(),
                            original.getVectorScore(), original.getBm25Score());
                })
                .collect(Collectors.toList());
    }

    /**
     * 生成 RRF 融合去重键：优先使用片段主键 {@code chunk_id}，缺失时返回 null（跳过融合）。
     */
    private String fusionKey(TextSegment segment) {
        String chunkId = segment.metadata().getString("chunk_id");
        if (chunkId == null || chunkId.isBlank()) {
            return null;
        }
        return "chunk:" + chunkId;
    }

    /**
     * <h3>生成文档唯一标识 Key（生产链路）</h3>
     *
     * <p>key = 文本前 100 字符 + "|" + source。同一文档在两路检索中可能返回略有不同的
     * TextSegment 对象，但通过相同的 Key 可以识别为同一文档。</p>
     *
     * @param segment 文本片段
     * @return 文档唯一标识 Key
     */
    private String generateKey(TextSegment segment) {
        String text = segment.text();
        // 截取文本前 100 个字符作为内容标识，足够区分不同文档
        String prefix = text.length() > 100 ? text.substring(0, 100) : text;
        // 从 metadata 中获取 source（文档来源标识）
        String source = segment.metadata().getString("source");
        // 组合：内容前缀 + 分隔符 + 来源
        return prefix + "|" + (source != null ? source : "unknown");
    }
}
