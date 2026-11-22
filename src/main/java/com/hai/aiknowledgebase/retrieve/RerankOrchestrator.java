package com.hai.aiknowledgebase.retrieve;

import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import com.hai.aiknowledgebase.service.ParamValueProvider;
import com.hai.aiknowledgebase.service.RerankerService;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <h2>Rerank 编排器（降权 / 过滤 / 重排）</h2>
 *
 * <p>收敛生产链路、召回测试链路与评测链路共用的事后处理逻辑：</p>
 * <ul>
 *   <li>{@link #rerank}：调用 Cross-Encoder 精排并用精排分替换原始分</li>
 *   <li>{@link #filterByMinScore}：按分数阈值过滤，全部剔除时降级保留</li>
 *   <li>{@link #applyContentTypeWeighting}：按 content_type 权重降权（粗排结果）</li>
 *   <li>{@link #applyContentTypeWeightingToReranked}：按 content_type 权重降权（精排结果）</li>
 *   <li>{@link #filterByExcludeKeywords}：排除关键词分词精确匹配过滤</li>
 *   <li>{@link #reorderByRawScore}：跨库合并后按原始向量/BM25 分重排</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankOrchestrator {

    /** Cross-Encoder Rerank 服务，对检索结果精排 */
    private final RerankerService rerankerService;

    /** 参数值读取器，按作用域动态读取 content_type 权重 */
    private final ParamValueProvider paramValueProvider;

    /** 中文分词服务，用于排除关键词的词级精确匹配（避免子串误杀） */
    private final ChineseTokenizerService tokenizerService;

    /**
     * <h3>Cross-Encoder 精排</h3>
     *
     * <p>对候选片段 (query, doc) 联合编码精排，并用精排分替换原始分数
     * （不保留 vectorScore / bm25Score，与重构前行为一致）。</p>
     *
     * @param query      用户查询文本
     * @param candidates 待精排的候选片段
     * @return 按精排分排列的结果列表
     */
    public List<RankedResult> rerank(String query, List<RankedResult> candidates) {
        List<String> docTexts = candidates.stream()
                .map(r -> r.getSegment().text())
                .collect(Collectors.toList());

        List<RerankerService.RerankResult> rerankResults =
                rerankerService.rerank(query, docTexts, candidates.size());

        List<RankedResult> reranked = new ArrayList<>();
        for (RerankerService.RerankResult rr : rerankResults) {
            RankedResult original = candidates.get(rr.getIndex());
            reranked.add(new RankedResult(original.getSegment(), rr.getScore()));
        }
        return reranked;
    }

    /**
     * <h3>Rerank 分数阈值过滤</h3>
     *
     * <p>剔除分数低于阈值的片段，避免噪音进入 LLM 上下文；
     * 若剔除后为空则降级保留原始结果。</p>
     *
     * @param results  待过滤的结果（已按分数降序）
     * @param minScore 最低分数阈值
     * @return 过滤后的结果列表
     */
    public List<RankedResult> filterByMinScore(List<RankedResult> results, double minScore) {
        int beforeFilter = results.size();
        List<RankedResult> filtered = results.stream()
                .filter(r -> r.getScore() >= minScore)
                .collect(Collectors.toList());
        if (filtered.isEmpty() && beforeFilter > 0) {
            log.warn("分数阈值过滤剔除了所有片段({} -> 0)，降级保留全部", beforeFilter);
            filtered = results;
        }
        log.info("分数阈值过滤: {} -> {} 个片段 (阈值={})", beforeFilter, filtered.size(),
                String.format("%.2f", minScore));
        return filtered;
    }

    /**
     * <h3>根据 content_type 对片段降权</h3>
     *
     * <p>试卷/试题文档中，正文（PASSAGE）权重保持不变，题目（QUESTION）、
     * 词汇表（VOCAB）、答案（ANSWER）的分数乘以折扣系数，确保检索时正文优先。</p>
     *
     * @param results 已排序的检索结果
     * @param repKbId 代表知识库 ID（null 表示仅读全局）
     * @return 降权后的结果列表
     */
    public List<RankedResult> applyContentTypeWeighting(List<RankedResult> results, Long repKbId) {
        List<RankedResult> weighted = new ArrayList<>();
        int passageCount = 0;
        int nonPassageCount = 0;

        for (RankedResult result : results) {
            String contentType = getContentTypeOf(result.getSegment());

            double weight = getContentTypeWeight(contentType, repKbId);
            double newScore = result.getScore() * weight;

            weighted.add(new RankedResult(result.getSegment(), newScore));

            if ("passage".equals(contentType) || contentType == null) {
                passageCount++;
            } else {
                nonPassageCount++;
            }
        }

        // 按新分数重新排序
        weighted.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        if (nonPassageCount > 0) {
            log.info("content_type 降权: 正文/无标记={} 个, 非正文={} 个 (题目/词汇/答案)",
                    passageCount, nonPassageCount);
        }
        return weighted;
    }

    /**
     * <h3>对 Rerank 精排结果执行 content_type 降权</h3>
     *
     * <p>与 {@link #applyContentTypeWeighting} 逻辑一致，作用于 {@link RerankedResult} 列表：
     * 按 content_type 权重乘以精排分数后降序重排，coarseRank（粗排位次）保持不变。</p>
     *
     * @param results 精排结果列表
     * @param repKbId 代表知识库 ID（null 表示仅读全局）
     */
    public List<RerankedResult> applyContentTypeWeightingToReranked(List<RerankedResult> results, Long repKbId) {
        if (results.isEmpty()) {
            return results;
        }
        List<RerankedResult> weighted = new ArrayList<>();
        int passageCount = 0;
        int nonPassageCount = 0;

        for (RerankedResult rr : results) {
            String contentType = getContentTypeOf(rr.getSegment());
            double weight = getContentTypeWeight(contentType, repKbId);
            weighted.add(new RerankedResult(
                    rr.getSegment(), rr.getRerankScore() * weight, rr.getCoarseRank()));

            if ("passage".equalsIgnoreCase(contentType) || contentType == null) {
                passageCount++;
            } else {
                nonPassageCount++;
            }
        }

        // 按降权后的精排分数重新排序
        weighted.sort((a, b) -> Double.compare(b.getRerankScore(), a.getRerankScore()));

        if (nonPassageCount > 0) {
            log.info("content_type 降权(精排后): 正文/无标记={} 个, 非正文={} 个 (题目/词汇/答案)",
                    passageCount, nonPassageCount);
        }
        return weighted;
    }

    /**
     * 获取 content_type 的分数权重系数（硬编码默认值，不读动态参数）。
     * 供精排路径 fallback 使用，保持行为不变。
     */
    private double getContentTypeWeight(String contentType) {
        if (contentType == null) {
            return 1.0;  // 无标记，保持原分
        }
        return switch (contentType.toLowerCase()) {
            case "passage" -> 1.0;
            case "question" -> 0.3;
            case "vocab" -> 0.3;
            case "answer" -> 0.5;
            case "mixed" -> 0.7;
            default -> 1.0;
        };
    }

    /**
     * 获取 content_type 的分数权重系数（动态参数优先，硬编码兜底）。
     *
     * <p>按 contentType 映射到参数键 {@code contentType.{type}.weight}，以代表 kbId 读取：
     * 优先 KB 级覆盖 → 回退全局 → 最后回退硬编码默认值。passage 与未知类型权重恒为 1.0，
     * 不发起参数查询。</p>
     *
     * @param contentType 内容类型（不区分大小写）
     * @param repKbId     代表知识库 ID（null 表示仅读全局）
     */
    private double getContentTypeWeight(String contentType, Long repKbId) {
        double fallback = getContentTypeWeight(contentType);
        if (fallback == 1.0) {
            return 1.0;
        }
        return paramValueProvider.getDouble(
                "contentType." + contentType.toLowerCase() + ".weight", repKbId, fallback);
    }

    /**
     * 安全读取片段元数据中的 content_type（无标记时返回 null）。
     */
    private String getContentTypeOf(TextSegment segment) {
        try {
            return segment.metadata().getString("content_type");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * <h3>排除关键词过滤</h3>
     *
     * <p>对片段分词后精确匹配排除词（避免子串误杀，如排除"Java"误杀"JavaScript"）。
     * 若过滤后结果为空，则降级保留原始结果，避免全部剔除导致无内容可返回。</p>
     *
     * @param results         待过滤的检索结果
     * @param excludeKeywords 排除关键词列表（null/空 表示不过滤）
     * @return 过滤后的结果列表
     */
    public List<RankedResult> filterByExcludeKeywords(List<RankedResult> results, List<String> excludeKeywords) {
        if (excludeKeywords == null || excludeKeywords.isEmpty() || results.isEmpty()) {
            return results;
        }
        int beforeFilter = results.size();
        List<RankedResult> filtered = results.stream()
                .filter(rr -> {
                    String text = rr.getSegment().text();
                    List<String> segTokens = tokenizerService.tokenize(text, true);
                    Set<String> tokenSet = new HashSet<>(segTokens);
                    for (String exclude : excludeKeywords) {
                        if (tokenSet.contains(exclude)) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        // 降级：过滤后结果为空，保留原始结果并记录警告
        if (filtered.isEmpty() && beforeFilter > 0) {
            log.warn("排除关键词过滤导致所有片段被剔除({} -> 0)，降级保留原始结果", beforeFilter);
            return results;
        }
        log.info("排除关键词过滤: {} -> {} 个片段", beforeFilter, filtered.size());
        return filtered;
    }

    /**
     * <h3>跨库重排（Rerank 关闭时）：按原始分重排并重指 score</h3>
     *
     * <p>跨库合并后绝不能用 RRF 融合分做最终排序，否则不同知识库的片段会被 RRF 分
     * 混排，掩盖真实的向量相似度差距。本方法按原始向量相似度 {@code vectorScore} 降序，
     * 无向量分时回退 BM25 分 {@code bm25Score}，两者皆无则回退原 {@code score}；
     * 并把 {@code score} 重指为实际排序值，确保后续降权内部重新 sort 时使用的是真实相似度而非 RRF 分。</p>
     *
     * @param results 待重排的检索结果（已携带 vectorScore / bm25Score）
     * @return 重排后的结果列表，score 已重指为排序依据值
     */
    public List<RankedResult> reorderByRawScore(List<RankedResult> results) {
        return results.stream()
                .map(r -> {
                    double sortValue = (r.getVectorScore() != null)
                            ? r.getVectorScore()
                            : (r.getBm25Score() != null ? r.getBm25Score() : r.getScore());
                    return new RankedResult(
                            r.getSegment(), sortValue, r.getVectorScore(), r.getBm25Score());
                })
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
    }
}
