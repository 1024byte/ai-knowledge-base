package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.SearchResult;
import com.hai.aiknowledgebase.queryrewrite.QueryRewriteService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <h2>RAG 检索与生成服务</h2>
 *
 * <p>核心职责：编排多策略检索流程，将检索到的文档片段构建为上下文，调用 LLM 生成最终答案。</p>
 *
 * <h3>检索流程（四阶段级联）</h3>
 * <pre>
 * 用户查询 "配置向量数据库"
 *     │
 *     ├── 阶段1：混合检索（向量 + BM25）── 主检索路径，取语义和关键词之长
 *     │
 *     ├── 阶段2：排除关键词过滤 ── 剔除包含排除词的片段（如 "不要XX"）
 *     │
 *     ├── 阶段3：Cross-Encoder Rerank 精排 ── 对候选片段精排，提升 Top-K 精度
 *     │
 *     └──→ 最终按分数降序重排序
 * </pre>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>混合检索</b>：向量语义检索 + BM25 关键词检索，RRF 融合排序</li>
 *   <li><b>Rerank 精排</b>：Cross-Encoder (BGE-Reranker-v2-m3) 对 (query, doc) 联合编码</li>
 *   <li><b>降级机制</b>：Reranker 不可用时跳过精排，排除词过滤全部剔除时保留原始结果</li>
 * </ul>
 *
 * @see HybridSearchService 混合检索服务（向量 + BM25）
 * @see RerankerService Cross-Encoder Rerank 精排服务
 * @see QueryRewriteService 查询改写服务（提供扩展词和排除词）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGSearchService {

    /** LangChain4j 向量存储（PGVector），用于向量相似度检索 */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** LangChain4j 嵌入模型，用于将文本转为向量 */
    private final EmbeddingModel embeddingModel;

    /** LangChain4j 聊天模型（DeepSeek），用于最终答案生成 */
    private final OpenAiChatModel chatModel;

    /** 混合检索服务，并行执行向量检索和 BM25 关键词检索 */
    private final HybridSearchService hybridSearchService;

    /** 中文分词服务，用于排除关键词的词级精确匹配（避免子串误杀） */
    private final ChineseTokenizerService tokenizerService;

    /** Cross-Encoder Rerank 服务，对检索结果精排 */
    private final RerankerService rerankerService;

    /** 默认检索返回数量（无过滤时） */
    private static final int DEFAULT_MAX_RESULTS = 5;

    /** 文件名过滤模式下的检索返回数量（扩大范围以提高命中率） */
    private static final int MAX_RESULTS_WHEN_FILTERED = 20;

    /** 向量检索最低余弦相似度阈值，低于此值的片段视为无关结果（0.6 分仍无关则需 &gt; 0.6） */
    private static final double MIN_VECTOR_SCORE = 0.3;

    // ==================== 核心检索方法 ====================

    /**
     * <h3>检索文档片段（简化版）</h3>
     *
     * <p>不传入扩展词和排除词，仅执行混合检索 + Rerank 精排。</p>
     *
     * @param userQuery 用户原始查询
     * @param topK      返回结果数量上限
     * @return 检索到的文档片段列表
     */
    public List<HybridSearchService.RankedResult> retrieveSegments(String userQuery, int topK) {
        return retrieveSegments(userQuery, topK, null, null);
    }

    /**
     * <h3>检索文档片段（三阶段级联 + 分数重排序）</h3>
     *
     * <h4>阶段1：混合检索</h4>
     * <p>并行执行向量语义检索和 BM25 关键词检索，RRF 融合排序，返回带分数结果。</p>
     *
     * <h4>阶段2：排除关键词过滤</h4>
     * <p>遍历所有结果片段，剔除包含排除关键词的片段，确保不返回用户明确不要的内容。</p>
     *
     * <h4>阶段3：Cross-Encoder Rerank 精排</h4>
     * <p>使用 BGE-Reranker-v2-m3 对候选片段进行 Cross-Encoder 精排，
     * 将 (query, doc) 联合编码，捕捉 Bi-Encoder 无法建模的词间交互，显著提升 Top-K 精度。</p>
     *
     * <h4>最终重排序</h4>
     * <p>所有阶段的结果合并后，按分数降序统一重排序，确保最相关的片段排在前面。</p>
     *
     * @param userQuery       用户原始查询（经查询改写后的 rewrittenQuery）
     * @param topK            返回结果数量上限
     * @param expandKeywords  扩展关键词（已废弃，由阶段1混合检索的BM25覆盖）
     * @param excludeKeywords 排除关键词列表
     * @return 按分数降序排列的带分数文档片段列表
     */
    public List<HybridSearchService.RankedResult> retrieveSegments(String userQuery, int topK,
                                              List<String> expandKeywords,
                                              List<String> excludeKeywords) {
        log.info("开始检索，用户查询: {} | 扩展词: {} | 排除词: {}",
                userQuery, expandKeywords, excludeKeywords);

        // ===== 阶段1：混合检索（主路径）=====
        // 并行执行向量检索 + BM25 关键词检索，RRF 融合排序
        // minVectorScore 过滤掉余弦相似度低于阈值的向量结果，避免无关片段进入融合

        List<HybridSearchService.RankedResult> allResults = new ArrayList<>(
                hybridSearchService.hybridSearchRanked(userQuery, 10, MIN_VECTOR_SCORE));

        log.info("混合检索召回 {} 个片段", allResults.size());

        // ===== 阶段2：排除关键词过滤 =====
        // 对片段分词后精确匹配排除词（避免子串误杀，如排除"Java"误杀"JavaScript"）
        if (excludeKeywords != null && !excludeKeywords.isEmpty()) {
            int beforeFilter = allResults.size();
            List<HybridSearchService.RankedResult> filtered = allResults.stream()
                    .filter(rr -> {
                        String text = rr.getSegment().text();
                        // 对片段文本分词，构建词集合用于精确匹配
                        List<String> segTokens = tokenizerService.tokenize(text, true);
                        Set<String> tokenSet = new HashSet<>(segTokens);
                        // 如果任一排除词作为独立词出现在片段中，则过滤掉
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
            } else {
                allResults = filtered;
            }
            log.info("排除关键词过滤: {} -> {} 个片段", beforeFilter, allResults.size());
        }

        // ===== 阶段3：Cross-Encoder Rerank 精排 =====
        // 使用 BGE-Reranker-v2-m3 对候选片段进行 Cross-Encoder 精排
        // Cross-Encoder 将 (query, doc) 联合编码，捕捉 Bi-Encoder 无法建模的词间交互
        if (rerankerService.isAvailable() && !allResults.isEmpty()) {
            log.info("开始 Rerank 精排，候选片段数: {}", allResults.size());

            List<String> docTexts = allResults.stream()
                    .map(r -> r.getSegment().text())
                    .collect(Collectors.toList());

            List<RerankerService.RerankResult> rerankResults =
                    rerankerService.rerank(userQuery, docTexts, allResults.size());

            // 按 Rerank 分数重建结果列表
            List<HybridSearchService.RankedResult> reranked = new ArrayList<>();
            for (RerankerService.RerankResult rr : rerankResults) {
                HybridSearchService.RankedResult original = allResults.get(rr.getIndex());
                // 用 Rerank 分数替换原始分数
                reranked.add(new HybridSearchService.RankedResult(original.getSegment(), rr.getScore()));
            }
            allResults = reranked;

            log.info("Rerank 精排完成，Top-3 分数: {}",
                    allResults.stream().limit(3)
                            .map(r -> String.format("%.4f", r.getScore()))
                            .collect(Collectors.joining(", ")));
        } else if (rerankerService.isEnabled() && !rerankerService.isAvailable()) {
            log.info("Reranker 已启用但未初始化，跳过精排");
        }

        // ===== 最终重排序：按分数降序统一排序 =====
        // 阶段1(RRF分数) + 阶段2(向量相似度) 的结果混合后，按分数统一重排序
        // 确保最相关的片段排在前面，缓解 LLM "lost in the middle" 问题
        allResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        log.info("分数重排序完成，Top-3 分数: {}",
                allResults.stream().limit(3)
                        .map(r -> String.format("%.4f", r.getScore()))
                        .collect(Collectors.joining(", ")));

        log.info("最终返回 {} 个片段", allResults.size());
        return allResults;
    }
    // ==================== 底层检索方法 ====================

    /**
     * <h3>纯向量语义检索</h3>
     *
     * <p>直接调用 EmbeddingModel 将查询文本转为向量，在 PGVector 向量库中
     * 执行 ANN（近似最近邻）搜索，返回相似度最高的文档片段。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li><b>embeddingModel.embed(query).content()</b>：将查询文本转为高维向量</li>
     *   <li><b>EmbeddingSearchRequest</b>：构建检索请求，指定查询向量和返回数量</li>
     *   <li><b>embeddingStore.search(request)</b>：在 PGVector 中执行 ANN 搜索</li>
     *   <li>从 EmbeddingMatch 中提取 TextSegment 返回</li>
     * </ol>
     *
     * @param userQuery 用户查询文本
     * @param topK      返回结果数量上限
     * @return 按余弦相似度降序排列的文档片段列表
     */
    private List<TextSegment> semanticSearch(String userQuery, int topK) {
        // 步骤1：将查询文本转为向量（最耗时步骤，通常 10-50ms）
        Embedding queryEmbedding = embeddingModel.embed(userQuery).content();

        // 步骤2：构建检索请求
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .build();

        // 步骤3：执行向量检索，提取 TextSegment
        return embeddingStore.search(request).matches().stream()
                .map(EmbeddingMatch::embedded)
                .collect(Collectors.toList());
    }

    // ==================== 上下文构建与答案生成 ====================

    /**
     * <h3>构建检索上下文（带文件名）</h3>
     *
     * <p>将检索到的文档片段格式化为 LLM 可理解的上下文文本。</p>
     *
     * <h4>输出格式</h4>
     * <pre>
     * 以下内容来自文件: 章程.pdf
     *
     * 【片段1】
     * 第一章 总则...
     *
     * 【片段2】
     * 第二章 组织架构...
     * </pre>
     *
     * <p>每个片段以 "【片段N】" 开头，方便 LLM 定位和引用来源。</p>
     *
     * @param segments 检索到的文档片段列表
     * @param fileName 文件来源名称（可为 null）
     * @return 格式化后的上下文字符串
     */
    public String buildContext(List<TextSegment> segments, String fileName) {
        if (segments.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // 如果指定了文件名，在开头添加来源声明
        if (fileName != null) {
            sb.append("以下内容来自文件: ").append(fileName).append("\n\n");
        }

        // 逐个片段编号并追加
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            sb.append("【片段").append(i + 1).append("】\n");
            sb.append(seg.text()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * <h3>构建检索上下文（无文件名）</h3>
     *
     * @param segments 检索到的文档片段列表
     * @return 格式化后的上下文字符串
     */
    private String buildContext(List<TextSegment> segments) {
        return buildContext(segments, null);
    }

    /**
     * <h3>生成答案（带文件名感知）</h3>
     *
     * <p>基于检索到的上下文，调用 LLM（DeepSeek Chat）生成最终答案。</p>
     *
     * <h4>两种 Prompt 策略</h4>
     * <ul>
     *   <li><b>文件名检索模式</b>（isFileNameSearch=true）：
     *       提示词强调"用户正在查找特定文件"，引导 LLM 明确告知是否在文件中找到答案</li>
     *   <li><b>通用检索模式</b>（isFileNameSearch=false）：
     *       标准 RAG 提示词，要求 LLM 基于片段回答，无法回答时明确告知</li>
     * </ul>
     *
     * <h4>关键设计</h4>
     * <p>两种模式都要求 LLM 在无法找到答案时<b>明确告知</b>，而不是编造内容。
     * 这是 RAG 场景下防止幻觉（Hallucination）的关键约束。</p>
     *
     * @param userQuery       用户原始查询
     * @param context         检索到的上下文文本
     * @param isFileNameSearch 是否为文件名检索模式
     * @return LLM 生成的答案
     */
    public String generateAnswer(String userQuery, String context, boolean isFileNameSearch) {
        String prompt;
        if (isFileNameSearch) {
            // 文件名检索模式：告知 LLM 用户正在查找特定文件
            prompt = String.format(
                    "用户正在查找特定文件的内容。请基于以下文档片段回答问题，如果无法从片段中找到答案，请明确告知。\n\n" +
                            "文档内容：\n%s\n\n" +
                            "问题：%s\n" +
                            "回答：",
                    context, userQuery
            );
        } else {
            // 通用检索模式：标准 RAG 回答
            prompt = String.format(
                    "基于以下文档片段回答问题。如果无法从片段中找到答案，请明确告知。\n\n" +
                            "文档内容：\n%s\n\n" +
                            "问题：%s\n" +
                            "回答：",
                    context, userQuery
            );
        }

        // 调用 LangChain4j 聊天模型生成答案
        return chatModel.chat(prompt);
    }

    /**
     * <h3>生成答案（通用模式）</h3>
     *
     * @param userQuery 用户原始查询
     * @param context   检索到的上下文文本
     * @return LLM 生成的答案
     */
    private String generateAnswer(String userQuery, String context) {
        return generateAnswer(userQuery, context, false);
    }

    // ==================== 端到端方法 ====================

    /**
     * <h3>端到端检索 + 答案生成</h3>
     *
     * <p>将检索和生成两个阶段串联为一个便捷方法，适合简单的单轮 Q&A 场景。</p>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li>调用 {@link #retrieveSegments} 执行三阶段级联检索</li>
     *   <li>如果检索结果为空，返回兜底提示语</li>
     *   <li>构建格式化上下文</li>
     *   <li>调用 LLM 生成最终答案</li>
     * </ol>
     *
     * @param userQuery 用户原始查询
     * @return LLM 生成的答案，或未找到信息时的兜底提示
     */
    public String searchAndAnswer(String userQuery) {
        // 步骤1：执行三阶段级联检索
        List<HybridSearchService.RankedResult> rankedResults = retrieveSegments(userQuery, DEFAULT_MAX_RESULTS);
        List<TextSegment> segments = rankedResults.stream()
                .map(HybridSearchService.RankedResult::getSegment)
                .collect(Collectors.toList());

        // 步骤2：检索结果为空时返回兜底提示
        if (segments.isEmpty()) {
            return "未找到相关信息，请尝试更换关键词或指定文件名进行搜索。";
        }

        // 步骤3：构建格式化上下文
        String context = buildContext(segments);

        // 步骤4：调用 LLM 生成答案
        return generateAnswer(userQuery, context);
    }

    /**
     * <h3>检索并返回 SearchResult 列表</h3>
     *
     * <p>适用于需要原始检索结果（不经过 LLM 生成）的场景，如检索结果展示、调试等。</p>
     *
     * <h4>与 searchAndAnswer 的区别</h4>
     * <ul>
     *   <li><b>searchAndAnswer</b>：检索 → 构建上下文 → LLM 生成答案 → 返回 String</li>
     *   <li><b>search</b>：检索 → 包装为 SearchResult → 返回列表</li>
     * </ul>
     *
     * @param query 用户查询文本
     * @param topK  返回结果数量上限
     * @return SearchResult 列表，包含内容、分数和来源
     */
    public List<SearchResult> search(String query, int topK) {
        // 执行四阶段级联检索
        List<HybridSearchService.RankedResult> rankedResults = retrieveSegments(query, topK);

        // 将 RankedResult 转为 SearchResult DTO（携带真实分数）
        return rankedResults.stream()
                .map(rr -> {
                    // 从 metadata 中提取 source 字段作为文档来源
                    String source = rr.getSegment().metadata().getString("source");
                    return new SearchResult(
                            rr.getSegment().text(),                               // 片段文本
                            rr.getScore(),                                        // 检索分数（RRF/向量相似度）
                            source != null ? source : "未知来源"                  // 文档来源
                    );
                })
                .collect(Collectors.toList());
    }
}