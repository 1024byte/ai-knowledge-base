package com.hai.aiknowledgebase.queryrewrite;

import com.hai.aiknowledgebase.dto.IntentResult;
import com.hai.aiknowledgebase.dto.QueryIntent;
import com.hai.aiknowledgebase.dto.QueryRewriteResult;
import com.hai.aiknowledgebase.dto.RewritePath;
import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import com.hai.aiknowledgebase.service.IntentRecognitionOrchestrator;
import com.hai.aiknowledgebase.service.QueryRewriteConfigLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class L2NLPBasedTransformer {

    /** 词典配置加载器：提供同义词词典、固定映射、停用词，支持定时热加载 */
    private final QueryRewriteConfigLoader configLoader;
    /** 中文分词服务：提供全模式分词和关键词提取 */
    private final ChineseTokenizerService tokenizerService;
    /** 意图识别服务：提供意图识别和改写提示词 */
    private final IntentRecognitionOrchestrator orchestrator;


    /**
     * L2 NLP 增强改写（NLP-enhanced Rewrite）
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>使用 L1 结果</b>：纠错已在主流程中完成，直接使用 L1 改写后的查询</li>
     *   <li><b>中文分词</b>：调用 {@link ChineseTokenizerService#tokenize(String, boolean)} 全模式分词</li>
     *   <li><b>意图识别</b>：调用 {@link IntentRecognitionOrchestrator#recognize(String)} 识别查询意图</li>
     *   <li><b>关键词提取</b>：调用 {@link ChineseTokenizerService#extractKeywords(String, int)} 提取 Top-5 关键词</li>
     *   <li><b>同义词扩展</b>：对原始查询分词结果查同义词词典，获取纯同义词</li>
     *   <li><b>意图策略选择</b>：根据意图类型选择对应的改写策略和追加词</li>
     *   <li><b>继承排除词</b>：直接从 L1 结果中继承排除关键词</li>
     *   <li><b>置信度计算</b>：四维加权（关键词数量 + 意图明确度 + 同义词命中 + 分词质量）</li>
     * </ol>
     *
     * @param originalQuery 原始查询文本（L1 之前的查询）
     * @param l1Result      L1 规则改写结果
     * @return L2 增强改写结果
     */
    public QueryRewriteResult applyNlpRewrite(String originalQuery, QueryRewriteResult l1Result) {
        // 1. 使用 L1 改写结果（纠错已在主流程中完成，此处无需再次纠错）
        String correctedQuery = l1Result.getRewrittenQuery();

        // 2. 中文分词
        List<String> tokens = tokenizerService.tokenize(correctedQuery, true);

        // 3. 意图识别（策略链：规则引擎 → LLM 兜底）
        IntentResult intentResult = orchestrator.recognize(correctedQuery);
        QueryIntent intent = intentResult.primaryIntent();
        log.debug("L2 意图识别结果: {} (置信度: {}) | 分词: {}", intent, intentResult.confidence(), tokens);

        // 4. 关键词提取（基于分词结果）
        List<String> keywords = tokenizerService.extractKeywords(correctedQuery, 5);

        // 5. 同义词扩展：对 L1 改写后的查询分词，查同义词词典
        List<String> synonymExpanded = expandSynonyms(tokens);

        // 6. 按意图选择改写策略
        RewriteStrategy strategy = selectStrategy(intent, correctedQuery, keywords, synonymExpanded);

        // 7. 合并 LLM 改写提示词到扩展关键词（提升检索召回率）
        if (intentResult.rewriteHints() != null && !intentResult.rewriteHints().isEmpty()) {
            strategy.expandKeywords.addAll(intentResult.rewriteHints());
            // 去重：改写提示词可能与既有扩展词重复（final 字段不能重新赋值，用 clear + addAll 原地去重）
            List<String> deduped = strategy.expandKeywords.stream().distinct().collect(Collectors.toList());
            strategy.expandKeywords.clear();
            strategy.expandKeywords.addAll(deduped);
            log.debug("L2 合并 LLM 改写提示词: {}", intentResult.rewriteHints());
        }

        // 8. 继承 L1 排除词
        List<String> excludeKeywords = new ArrayList<>();
        if (l1Result.getExcludeKeywords() != null && !l1Result.getExcludeKeywords().isEmpty()) {
            excludeKeywords.addAll(l1Result.getExcludeKeywords());
        }

        // 9. 计算置信度（四维加权）
        double confidence = calculateL2Confidence(keywords, intent, tokens);

        return QueryRewriteResult.builder()
                .rewrittenQuery(strategy.rewrittenQuery)
                .expandKeywords(strategy.expandKeywords)
                .excludeKeywords(excludeKeywords)
                .confidence(confidence)
                .path(RewritePath.L2_NLP)
                .build();
    }

    /**
     * 同义词扩展：对分词结果中的每个词查找同义词
     *
     * <p>遍历所有 token，在 {@link QueryRewriteConfigLoader#getSynonymDict()} 中查找，
     * 将命中的同义词列表追加到结果中，最后去重返回。</p>
     *
     * <p>注意：同义词词典的 key 是原始词，value 是包含该词在内的同义词列表。
     * 因此命中后追加的是整个同义词组（包括原始词），调用方会通过
     * {@link #buildRewrittenQuery} 中的 contains 检查去重。</p>
     *
     * @param tokens 分词结果列表
     * @return 去重后的同义词列表
     */
    private List<String> expandSynonyms(List<String> tokens) {
        Map<String, List<String>> synonymDict = configLoader.getSynonymDict();
        List<String> expanded = new ArrayList<>();
        for (String token : tokens) {
            List<String> synonyms = synonymDict.get(token);
            if (synonyms != null && !synonyms.isEmpty()) {
                expanded.addAll(synonyms);
            }
        }
        return expanded.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 按意图选择改写策略
     *
     * <h3>策略映射</h3>
     * <table>
     *   <tr><th>意图</th><th>扩展关键词</th><th>改写查询</th></tr>
     *   <tr><td>FACTUAL（事实型）</td><td>关键词 + 同义词</td><td>关键词 + 同义词</td></tr>
     *   <tr><td>PROCEDURAL（过程型）</td><td>关键词 + 同义词</td><td>关键词 + 同义词 + 步骤/方法/教程/流程</td></tr>
     *   <tr><td>COMPARISON（对比型）</td><td>关键词 + 同义词</td><td>关键词 + 同义词 + 区别/对比/差异</td></tr>
     *   <tr><td>DEFINITIONAL（定义型）</td><td>关键词 + 同义词</td><td>关键词 + 同义词 + 定义/概念/含义</td></tr>
     *   <tr><td>AMBIGUOUS（模糊型）</td><td>仅原始关键词</td><td>仅原始查询（不改写）</td></tr>
     * </table>
     *
     * <h3>设计说明</h3>
     * <ul>
     *   <li><b>扩展关键词</b>：只保留精准词（同义词 + 关键词），用于独立向量检索和 BM25 匹配</li>
     *   <li><b>改写查询</b>：追加意图相关的描述词辅助语义匹配，但不作为独立检索词</li>
     *   <li><b>意图词</b>（步骤/方法/教程/定义/概念/区别/对比）：只出现在 rewrittenQuery 中，
     *       不进入 expandKeywords，避免"步骤""方法"等宽泛词独立检索产生噪音</li>
     *   <li><b>AMBIGUOUS</b>：模糊意图不做改写，避免引入噪声误导向量检索</li>
     * </ul>
     *
     * @param intent          查询意图类型
     * @param baseQuery       基础查询文本（纠错后的文本）
     * @param keywords        L2 提取的关键词
     * @param synonymExpanded 同义词扩展列表
     * @return 改写策略（含改写后查询和扩展关键词）
     */
    private RewriteStrategy selectStrategy(QueryIntent intent, String baseQuery,
                                                               List<String> keywords, List<String> synonymExpanded) {
        String rewrittenQuery;
        List<String> expandKeywords = new ArrayList<>(keywords);

        switch (intent) {
            case FACTUAL:
                expandKeywords.addAll(synonymExpanded);
                rewrittenQuery = buildRewrittenQuery(baseQuery, synonymExpanded);
                break;

            case PROCEDURAL:
                expandKeywords.addAll(synonymExpanded);
                List<String> proceduralAll = List.of("步骤", "方法", "教程", "流程");
                rewrittenQuery = buildRewrittenQuery(baseQuery, synonymExpanded, proceduralAll);
                break;

            case COMPARISON:
                expandKeywords.addAll(synonymExpanded);
                List<String> comparisonAll = List.of("区别", "对比", "差异");
                rewrittenQuery = buildRewrittenQuery(baseQuery, synonymExpanded, comparisonAll);
                break;

            case DEFINITIONAL:
                expandKeywords.addAll(synonymExpanded);
                List<String> defAll = List.of("定义", "概念", "含义");
                rewrittenQuery = buildRewrittenQuery(baseQuery, synonymExpanded, defAll);
                break;

            case AMBIGUOUS:
            default:
                // 原始查询不改写，仅关键词作为 BM25 扩展词
                rewrittenQuery = baseQuery;
                break;
        }

        expandKeywords = expandKeywords.stream().distinct().collect(Collectors.toList());
        return new RewriteStrategy(rewrittenQuery, expandKeywords);
    }

    /**
     * 构建改写后的查询文本（完整版）
     *
     * <h3>拼接规则</h3>
     * 以基础查询为起点，依次追加：
     * <ol>
     *   <li>基础查询（baseQuery）作为第一段</li>
     *   <li>不在 baseQuery 中的同义词（L1 已追加的同义词会被 contains 跳过）</li>
     *   <li>不在 baseQuery 和已追加内容中的意图追加词</li>
     * </ol>
      * 所有段落用空格拼接，形成最终的改写查询文本。
     *
     * <h3>注意事项</h3>
     * 使用 {@link String#contains(CharSequence)} 做子串匹配判断是否已存在，
     * 可能导致部分词被误判为"已存在"而跳过（如"数据"作为子串在"数据库"中匹配成功）。
     * 建议改用分词后精确匹配，但当前场景下此问题影响较小
     * （关键词通常不是其他词的子串）。
     *
     * @param baseQuery    基础查询文本
     * @param synonyms     同义词列表
     * @param intentExtras 意图追加词列表（可为 null）
     * @return 空格拼接的改写查询文本
     */
    private String buildRewrittenQuery(String baseQuery,
                                       List<String> synonyms, List<String> intentExtras) {
        List<String> allParts = new ArrayList<>();
        allParts.add(baseQuery);
        for (String syn : synonyms) {
            if (!baseQuery.contains(syn) && !allParts.contains(syn)) {
                allParts.add(syn);
            }
        }
        if (intentExtras != null) {
            for (String extra : intentExtras) {
                if (!baseQuery.contains(extra) && !allParts.contains(extra)) {
                    allParts.add(extra);
                }
            }
        }
        return String.join(" ", allParts);
    }

    /**
     * 构建改写后的查询文本（简化版：无意图追加词）
     */
    private String buildRewrittenQuery(String baseQuery, List<String> synonyms) {
        return buildRewrittenQuery(baseQuery, synonyms, null);
    }

    /**
     * L2 置信度计算（四维加权模型）
     *
     * <h3>维度与权重</h3>
     * <table>
     *   <tr><th>维度</th><th>权重</th><th>计算规则</th></tr>
     *   <tr><td>关键词数量</td><td>0.3</td><td>≥3个→1.0，2个→0.6，1个→0.3，0个→0.0</td></tr>
     *   <tr><td>意图明确度</td><td>0.3</td><td>AMBIGUOUS→0.0，其他→1.0</td></tr>
     *   <tr><td>同义词命中率</td><td>0.2</td><td>命中的关键词数 / 关键词总数</td></tr>
     *   <tr><td>分词质量</td><td>0.2</td><td>有效词（长度≥2且非纯数字）数 / token 总数</td></tr>
     * </table>
     *
     * <h3>设计说明</h3>
     * <ul>
     *   <li>关键词数量和意图明确度各占 0.3，是最重要的两个维度</li>
     *   <li>同义词命中率反映词典覆盖度，分词质量反映输入有效性</li>
     *   <li>最终结果保留两位小数（乘以 100 后取整再除以 100）</li>
     * </ul>
     *
     * <h3>性能考量</h3>
     * 同义词命中率计算中，对每个关键词遍历所有同义词 values 做匹配，
     * 复杂度为 O(k × v)，其中 k 为关键词数，v 为同义词总数。
     * 在词典规模较大时可能成为瓶颈，可考虑预构建反向索引优化。
     *
     * @param keywords 关键词列表
     * @param intent   查询意图
     * @param tokens   分词 token 列表
     * @return 置信度（0.0 ~ 1.0）
     */
    private double calculateL2Confidence(List<String> keywords, QueryIntent intent, List<String> tokens) {
        // 1. 关键词数量得分 (权重 0.3)
        double keywordScore;
        if (keywords == null || keywords.isEmpty()) {
            keywordScore = 0.0;
        } else if (keywords.size() >= 3) {
            keywordScore = 1.0;
        } else if (keywords.size() == 2) {
            keywordScore = 0.6;
        } else {
            keywordScore = 0.3;
        }

        // 2. 意图明确度得分 (权重 0.3)
        double intentScore;
        if (intent == QueryIntent.AMBIGUOUS) {
            intentScore = 0.0;
        } else {
            intentScore = 1.0;
        }

        // 3. 同义词命中数得分 (权重 0.2)
        double synonymScore = 0.0;
        if (keywords != null && !keywords.isEmpty()) {
            Map<String, List<String>> synonymDict = configLoader.getSynonymDict();
            // 预构建同义词 values 集合，避免对每个关键词遍历所有 values（O(k×v) → O(k)）
            Set<String> allSynonymValues = new HashSet<>();
            for (List<String> vals : synonymDict.values()) {
                if (vals != null) {
                    allSynonymValues.addAll(vals);
                }
            }
            long hitCount = keywords.stream()
                    .filter(kw -> synonymDict.containsKey(kw) || allSynonymValues.contains(kw))
                    .count();
            synonymScore = (double) hitCount / keywords.size();
        }

        // 4. 分词质量得分 (权重 0.2)
        double tokenQualityScore = 0.0;
        if (tokens != null && !tokens.isEmpty()) {
            long validCount = tokens.stream()
                    .filter(t -> t.length() >= 2 && !t.matches("\\d+"))
                    .count();
            tokenQualityScore = (double) validCount / tokens.size();
        }

        // 加权求和
        double confidence = keywordScore * 0.3 + intentScore * 0.3 + synonymScore * 0.2 + tokenQualityScore * 0.2;

        return Math.round(confidence * 100) / 100.0;
    }

    /**
     * 改写策略：封装意图选择后的改写结果
     *
     * <p>由 {@link #selectStrategy(QueryIntent, String, List, List)} 方法创建，
     * 包含改写后的查询文本（用于向量检索）和扩展关键词列表（用于 BM25 精确匹配）。</p>
     *
     * @param rewrittenQuery 改写后的查询文本
     * @param expandKeywords 扩展关键词列表（去重后）
     */
    private static class RewriteStrategy {
        final String rewrittenQuery;
        final List<String> expandKeywords;

        RewriteStrategy(String rewrittenQuery, List<String> expandKeywords) {
            this.rewrittenQuery = rewrittenQuery;
            this.expandKeywords = expandKeywords;
        }
    }


}