package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.common.KeywordIndex;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * <h2>混合检索服务</h2>
 *
 * <p>核心职责：将<b>向量语义检索</b>（Vector Search）和<b>关键词精确匹配</b>（BM25 Keyword Search）
 * 两路检索结果进行融合排序，输出最终的结果集。</p>
 *
 * <h3>为什么需要混合检索？</h3>
 * <ul>
 *   <li><b>向量检索</b>：擅长语义理解，能匹配"意思相近"的内容，但容易遗漏精确的关键词命中。</li>
 *   <li><b>关键词检索</b>：基于 BM25 算法，擅长精确匹配，但无法理解同义词、近义词等语义变体。</li>
 *   <li><b>混合检索</b>：取两者之长，通过 RRF 算法融合排序，兼顾语义相关性和关键词精确性。</li>
 * </ul>
 *
 * <h3>架构流程</h3>
 * <pre>
 * 用户查询 "配置向量数据库"
 *     │
 *     ├──→ [并行] 向量检索路径 ──→ EmbeddingModel.embed() → PGVector.search()
 *     │                          返回: 语义相关 Top-K 文档
 *     │
 *     └──→ [并行] 关键词检索路径 ──→ KeywordIndex.search() (BM25)
 *                                返回: 关键词精确匹配 Top-K 文档
 *     │
 *     └──→ [融合] RRF(Reciprocal Rank Fusion) 排序
 *          两路结果按排名加权融合，取最终 Top-K
 * </pre>
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>混合检索关闭时（hybridEnabled=false）→ 降级为纯向量检索</li>
 *   <li>关键词索引为空时（keywordIndex.size()==0）→ 降级为纯向量检索</li>
 *   <li>向量检索失败时 → 返回空列表，不阻塞整体流程</li>
 *   <li>关键词检索失败时 → 返回空列表，仅用向量结果参与 RRF 融合</li>
 * </ul>
 *
 * @see KeywordIndex 基于 BM25 的内存关键词索引
 * @see ChineseTokenizerService 中文分词服务
 */
@Slf4j
@Service
public class HybridSearchService {

    /** LangChain4j 嵌入模型，用于将查询文本转为向量 */
    private final EmbeddingModel embeddingModel;

    /** LangChain4j 向量存储（PGVector），用于向量相似度检索 */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** 基于 BM25 算法的内存关键词倒排索引 */
    private final KeywordIndex keywordIndex;

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

    /** JDBC Template，用于从 PGVector embeddings 表中读取数据重建索引 */
    private final JdbcTemplate jdbcTemplate;

    /** PGVector 向量表名，默认 "embeddings"，与 {@code vectorstore.pgvector.table-name} 配置一致 */
    @Value("${vectorstore.pgvector.table-name:embeddings}")
    private String tableName;

    /**
     * 构造混合检索服务。
     * 初始化时会创建 KeywordIndex 实例，后续文档入库时需调用 {@link #getKeywordIndex()} 来同步索引。
     *
     * @param embeddingModel    嵌入模型（LangChain4j）
     * @param embeddingStore    向量存储（PGVector）
     * @param tokenizerService  中文分词服务，用于 BM25 关键词索引的分词
     * @param jdbcTemplate      JDBC Template，用于启动时从 PGVector 重建 BM25 索引
     */
    public HybridSearchService(EmbeddingModel embeddingModel,
                               EmbeddingStore<TextSegment> embeddingStore,
                               ChineseTokenizerService tokenizerService,
                               JdbcTemplate jdbcTemplate) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.keywordIndex = new KeywordIndex(tokenizerService);
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 启动时 BM25 索引重建 ====================

    /**
     * <h3>应用启动时从 PGVector 重建 BM25 关键词索引</h3>
     *
     * <p>因为 KeywordIndex 是纯内存结构（{@link ConcurrentHashMap}），
     * 应用重启后索引数据会全部丢失。此方法在 Spring 容器初始化完成后自动执行，
     * 从 PGVector 的 embeddings 表中读取所有 chunk 数据，重建 BM25 倒排索引。</p>
     *
     * <h4>重建流程</h4>
     * <ol>
     *   <li>从 PGVector embeddings 表查询所有 chunk（text + metadata）</li>
     *   <li>按 {@code metadata->>'document_id'} 分组，组内按 {@code embedding_id} 排序</li>
     *   <li>为每组内的 chunk 分配顺序索引（0, 1, 2...），
     *       重构 chunkId 格式为 {@code "documentId_chunkIndex"}，
     *       与 {@link VectorizationService} 入库时保持完全一致</li>
     *   <li>调用 {@link KeywordIndex#index} 逐条重建索引</li>
     * </ol>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code metadata->>'document_id'}</b>：PostgreSQL JSON 操作符，
     *       从 metadata JSONB 字段中提取 document_id 文本值</li>
     *   <li><b>{@code ORDER BY embedding_id}</b>：保证同一文档的 chunk 顺序稳定，
     *       确保 chunkIndex 分配与原始入库时尽可能一致</li>
     *   <li><b>{@code @PostConstruct}</b>：Spring 生命周期注解，
     *       在 Bean 所有依赖注入完成后、对外提供服务前自动调用</li>
     *   <li><b>异常容错</b>：重建失败时记录错误日志但不抛异常，
     *       不阻塞应用启动。后续可通过重新上传文档逐步恢复索引</li>
     * </ul>
     *
     * <h4>性能考虑</h4>
     * <p>重建时会对每个 chunk 进行中文分词（jieba），如果 chunk 数量较多（如 10000+），
     * 启动时间会延长数秒。这是一个可接受的权衡，因为：
     * <ul>
     *   <li>启动多在非业务高峰期进行</li>
     *   <li>一次性重建后，后续增量由 VectorizationService 异步维护</li>
     *   <li>相比检索时丢失 BM25 精确匹配能力，启动延迟是可接受的代价</li>
     * </ul>
     */
    @PostConstruct
    public void rebuildKeywordIndex() {
        try {
            log.info("开始从 PGVector 重建 BM25 关键词索引...");
            long startTime = System.currentTimeMillis();

            // 查询所有 chunk：按 document_id 分组，组内按 embedding_id 排序
            // 这样能保证同一文档的 chunk 顺序与原始入库时一致
            String sql = String.format(
                    "SELECT embedding_id, text, metadata->>'document_id' AS doc_id " +
                    "FROM %s " +
                    "ORDER BY metadata->>'document_id', embedding_id",
                    tableName
            );
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            if (rows.isEmpty()) {
                log.info("PGVector 中无数据，BM25 索引为空，跳过重建");
                return;
            }

            // 按 document_id 分组，每组内按 embedding_id 顺序分配 chunkIndex
            String currentDocId = null;
            int chunkIndex = 0;
            int indexedCount = 0;

            for (Map<String, Object> row : rows) {
                String docId = String.valueOf(row.get("doc_id"));
                String text = String.valueOf(row.get("text"));

                // 跳过空文本
                if (text == null || text.isBlank()) {
                    continue;
                }

                // 检测到新的 document_id → 重置 chunkIndex
                if (!docId.equals(currentDocId)) {
                    currentDocId = docId;
                    chunkIndex = 0;
                }

                // 重构 chunkId：格式与 VectorizationService 入库时一致
                // 例如 docId="42", chunkIndex=0 → "42_0"
                String chunkId = docId + "_" + chunkIndex;
                keywordIndex.index(chunkId, text);
                chunkIndex++;
                indexedCount++;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("BM25 关键词索引重建完成: 共 {} 条 chunk, 耗时 {} ms",
                    indexedCount, elapsed);

        } catch (Exception e) {
            log.error("BM25 关键词索引重建失败，应用将继续运行但降级为纯向量检索", e);
            // 不抛异常，让应用继续启动。后续可通过重新上传文档逐步恢复
        }
    }

    /**
     * 获取关键词索引实例。
     * 外部服务（如 DocumentService）在文档入库时，需要调用此方法获取索引实例，
     * 将文档内容同步添加到关键词索引中。
     *
     * @return BM25 关键词倒排索引实例
     */
    public KeywordIndex getKeywordIndex() {
        return keywordIndex;
    }

    /**
     * 查询混合检索是否启用。
     *
     * @return true 表示启用混合检索，false 表示仅使用向量检索
     */
    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    // ==================== 核心检索方法 ====================

    /**
     * <h3>混合检索主入口</h3>
     *
     * <p>并行执行向量检索和关键词检索，使用 RRF 算法融合两路结果后返回最终排序。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>检查混合检索是否可用（启用开关 + 关键词索引非空）</li>
     *   <li>使用 {@link CompletableFuture#supplyAsync} 并行发起两路检索</li>
     *   <li>调用 {@link #reciprocalRankFusion} 进行 RRF 融合排序</li>
     *   <li>将排序后的结果转换为 TextSegment 列表返回</li>
     * </ol>
     *
     * <h4>降级逻辑</h4>
     * <ul>
     *   <li>混合检索关闭 或 关键词索引为空 → 降级为纯向量检索</li>
     * </ul>
     *
     * @param query 用户原始查询文本
     * @param topK  返回结果数量上限
     * @return 融合排序后的 TextSegment 列表（按 RRF 分数降序）
     */
    public List<TextSegment> hybridSearch(String query, int topK) {
        // 降级判断：如果混合检索关闭或关键词索引为空，直接走纯向量检索
        if (!hybridEnabled || keywordIndex.size() == 0) {
            log.info("混合检索已关闭或关键词索引为空，降级为纯向量检索");
            return vectorSearch(query, topK);
        }

        // 并行发起两路检索：向量路径 + 关键词路径
        // CompletableFuture.supplyAsync 使用默认的 ForkJoinPool.commonPool()
        // 两路检索互不阻塞，充分利用 CPU 和 IO 资源
        CompletableFuture<List<RankedResult>> vectorFuture =
                CompletableFuture.supplyAsync(() -> vectorSearchRanked(query, topK));
        CompletableFuture<List<RankedResult>> keywordFuture =
                CompletableFuture.supplyAsync(() -> keywordSearchRanked(query, topK));

        // join() 阻塞等待两路结果全部返回
        List<RankedResult> vectorResults = vectorFuture.join();
        List<RankedResult> keywordResults = keywordFuture.join();

        log.info("双路检索完成: 向量结果={}, 关键词结果={}", vectorResults.size(), keywordResults.size());

        // RRF 融合排序：将两路结果的排名按 RRF 公式加权合并
        List<RankedResult> fused = reciprocalRankFusion(vectorResults, keywordResults, topK);
        log.info("RRF 融合结果数量: {}", fused.size());

        // 提取 TextSegment 返回
        return fused.stream()
                .map(RankedResult::getSegment)
                .collect(Collectors.toList());
    }

    /**
     * <h3>纯向量检索</h3>
     *
     * <p>仅使用向量语义相似度进行检索，不涉及关键词匹配。</p>
     * <p>适用场景：混合检索关闭时降级使用，或作为独立检索接口。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>调用 EmbeddingModel 将查询文本转为向量</li>
     *   <li>在 PGVector 向量库中执行 ANN（近似最近邻）搜索</li>
     *   <li>返回相似度最高的 Top-K 文档</li>
     * </ol>
     *
     * @param query 用户查询文本
     * @param topK  返回结果数量上限
     * @return 按余弦相似度降序排列的 TextSegment 列表
     */
    public List<TextSegment> vectorSearch(String query, int topK) {
        return vectorSearchRanked(query, topK).stream()
                .map(RankedResult::getSegment)
                .collect(Collectors.toList());
    }

    // ==================== 私有检索方法 ====================

    /**
     * <h3>向量检索（带分数）</h3>
     *
     * <p>执行一次完整的向量语义检索，返回带有相似度分数的 RankedResult 列表。</p>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>embeddingModel.embed(query).content()</b>：
     *       调用 LangChain4j 的嵌入模型（本项目使用 BGE-small-zh 量化模型），
     *       将查询文本转换为高维向量（如 512 维）。这个向量捕获了查询的语义信息。</li>
     *   <li><b>EmbeddingSearchRequest</b>：
     *       构建向量检索请求，指定查询向量和返回数量。PGVector 底层使用
     *       IVFFlat 或 HNSW 索引进行 ANN 近似最近邻搜索。</li>
     *   <li><b>embeddingStore.search(request)</b>：
     *       在 PGVector 向量库中检索与查询向量最相似的文档片段，
     *       返回的 EmbeddingMatch 包含 TextSegment 和余弦相似度分数。</li>
     *   <li><b>异常处理</b>：
     *       向量检索失败时返回空列表，不抛异常，保证服务可用性。</li>
     * </ul>
     *
     * @param query 用户查询文本
     * @param topK  返回结果数量上限
     * @return 带相似度分数的排序结果列表
     */
    private List<RankedResult> vectorSearchRanked(String query, int topK) {
        try {
            // 步骤1：将查询文本转为向量（这是整个流程中最耗时的步骤，通常 10-50ms）
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            // 步骤2：构建检索请求，指定查询向量和返回数量上限
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .build();

            // 步骤3：在向量库中执行 ANN 搜索，获取匹配结果
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();

            // 步骤4：将 EmbeddingMatch 转为内部 RankedResult 格式
            List<RankedResult> results = new ArrayList<>();
            for (int i = 0; i < matches.size(); i++) {
                // EmbeddingMatch.embedded() 返回匹配的 TextSegment
                // EmbeddingMatch.score() 返回余弦相似度分数 (0.0~1.0)
                results.add(new RankedResult(matches.get(i).embedded(), matches.get(i).score()));
            }
            return results;
        } catch (Exception e) {
            log.error("向量检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * <h3>关键词检索（带分数）</h3>
     *
     * <p>基于 BM25 算法的关键词精确匹配检索，返回带有 BM25 分数的 RankedResult 列表。</p>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>keywordIndex.search(query, topK)</b>：
     *       调用 BM25 倒排索引进行全文检索。内部流程：
     *       <ol>
     *         <li>对查询文本进行分词（中文用 HanLP，英文按空格/标点分割）</li>
     *         <li>对每个查询词，在倒排索引中查找包含该词的文档列表</li>
     *         <li>计算 BM25 分数：score = IDF × TF × (k1+1) / (TF + k1×(1-b+b×docLen/avgLen))</li>
     *         <li>其中 IDF 为逆文档频率，TF 为词频，k1=1.2 和 b=0.75 为经典参数</li>
     *       </ol>
     *   </li>
     *   <li><b>extractSourceFromDocId(docId)</b>：
     *       从 docId 中提取文档来源标识。docId 格式为 "source_id_chunkIndex"，
     *       取最后一个下划线之前的部分作为 source。</li>
     *   <li><b>TextSegment.from(text, metadata)</b>：
     *       将关键词检索到的文本包装为 TextSegment，与向量检索结果格式统一，
     *       便于后续 RRF 融合。</li>
     *   <li><b>异常处理</b>：
     *       关键词检索失败时返回空列表，不影响向量检索结果参与 RRF 融合。</li>
     * </ul>
     *
     * @param query 用户查询文本
     * @param topK  返回结果数量上限
     * @return 带 BM25 分数的排序结果列表
     */
    private List<RankedResult> keywordSearchRanked(String query, int topK) {
        try {
            // 步骤1：在 BM25 倒排索引中检索，返回 (docId, BM25分数) 的有序列表
            List<Map.Entry<String, Double>> entries = keywordIndex.search(query, topK);

            List<RankedResult> results = new ArrayList<>();
            for (var entry : entries) {
                String docId = entry.getKey();

                // 步骤2：根据 docId 获取文档原文
                String text = keywordIndex.getDocText(docId);
                if (text == null) continue;

                // 步骤3：从 docId 中提取 source 标识
                // docId 格式: "documentName_chunkIndex"，如 "章程.pdf_5"
                String source = extractSourceFromDocId(docId);

                // 步骤4：包装为 TextSegment，与向量检索结果格式统一
                TextSegment segment = TextSegment.from(text,
                        new dev.langchain4j.data.document.Metadata()
                                .put("source", source)
                                .put("doc_id", docId));

                results.add(new RankedResult(segment, entry.getValue()));
            }
            return results;
        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return Collections.emptyList();
        }
    }

    // ==================== RRF 融合算法 ====================

    /**
     * <h3>RRF (Reciprocal Rank Fusion) 倒数排名融合算法</h3>
     *
     * <p>RRF 是一种经典的多路检索结果融合算法，核心思想是：
     * <b>某个文档在任一路检索中排名越靠前，最终得分越高</b>。</p>
     *
     * <h4>计算公式</h4>
     * <pre>
     * RRF_score(d) = Σ 1 / (k + rank_i(d))
     *
     * 其中：
     *   - d     : 目标文档
     *   - k     : 平滑参数（默认 60），防止排名靠前的文档权重过大
     *   - rank_i: 文档在第 i 路检索中的排名（从 1 开始）
     *   - Σ     : 对所有包含该文档的检索路径求和
     * </pre>
     *
     * <h4>为什么 k=60？</h4>
     * <ul>
     *   <li>k=60 是学术界和工业界验证的最佳实践值</li>
     *   <li>k 太小 → 排名靠前的文档权重过大，排名靠后的文档几乎无贡献</li>
     *   <li>k 太大 → 排名差异被过度平滑，排名信息失去意义</li>
     *   <li>k=60 在精确性和召回率之间取得了良好的平衡</li>
     * </ul>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li><b>向量排名贡献</b>：遍历向量检索结果，对每个文档累加其排名贡献分</li>
     *   <li><b>关键词排名贡献</b>：遍历关键词检索结果，对每个文档累加其排名贡献分</li>
     *   <li><b>合并去重</b>：同一文档在两路中都出现时，分数累加（更好！）</li>
     *   <li><b>排序截断</b>：按 RRF 总分降序排序，取 Top-K 返回</li>
     * </ol>
     *
     * <h4>为什么用 RRF 而不是简单的分数加权？</h4>
     * <ul>
     *   <li>向量相似度分数（0~1）和 BM25 分数（无上限）的量纲不同，直接加权需要归一化</li>
     *   <li>RRF 只关心排名，不关心绝对分数值，天然解决了量纲不一致的问题</li>
     *   <li>RRF 无需训练，无需调参（除 k 外），实现简单且效果稳定</li>
     * </ul>
     *
     * @param vectorResults   向量检索结果（按相似度降序）
     * @param keywordResults  关键词检索结果（按 BM25 分数降序）
     * @param topK            最终返回结果数量上限
     * @return RRF 融合排序后的结果列表
     */
    private List<RankedResult> reciprocalRankFusion(
            List<RankedResult> vectorResults,
            List<RankedResult> keywordResults,
            int topK) {

        // 使用 LinkedHashMap 保持插入顺序，用于最终结果映射
        // key: 文档唯一标识（由 generateKey 生成）
        Map<String, RankedResult> allResults = new LinkedHashMap<>();
        // key: 文档唯一标识，value: 该文档的 RRF 累加分数
        Map<String, Double> rrfScores = new HashMap<>();

        // 步骤1：计算向量检索路径的排名贡献
        // vectorResults 已按相似度降序排列，排名从 1 开始
        for (int i = 0; i < vectorResults.size(); i++) {
            String key = generateKey(vectorResults.get(i).getSegment());
            // putIfAbsent: 保留首次出现的 RankedResult（通常来自向量检索）
            allResults.putIfAbsent(key, vectorResults.get(i));
            // merge: 累加 RRF 分数，公式为 1/(k + rank)
            // rank = i + 1（因为 i 从 0 开始，排名从 1 开始）
            rrfScores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
        }

        // 步骤2：计算关键词检索路径的排名贡献
        // keywordResults 已按 BM25 分数降序排列，排名从 1 开始
        for (int i = 0; i < keywordResults.size(); i++) {
            String key = generateKey(keywordResults.get(i).getSegment());
            // putIfAbsent: 如果向量检索中已有该文档，不覆盖（保留向量检索的 RankedResult）
            allResults.putIfAbsent(key, keywordResults.get(i));
            // merge: 累加 RRF 分数
            // 如果同一文档在两路检索中都出现，RRF 分数会累加，排名会更高
            rrfScores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
        }

        // 步骤3：按 RRF 总分降序排序，取 Top-K
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    // 从 allResults 中取出原始 RankedResult，
                    // 用新的 RRF 总分替换原来的单一路径分数
                    RankedResult original = allResults.get(entry.getKey());
                    return new RankedResult(original.getSegment(), entry.getValue());
                })
                .collect(Collectors.toList());
    }

    // ==================== 辅助方法 ====================

    /**
     * <h3>生成文档唯一标识 Key</h3>
     *
     * <p>用于 RRF 融合时的文档去重判断。同一文档在两路检索中可能返回略有不同的
     * TextSegment 对象，但通过相同的 Key 可以识别为同一文档。</p>
     *
     * <h4>Key 的组成规则</h4>
     * <pre>
     * key = 文本前100字符 + "|" + source
     *
     * 示例: "向量数据库是一种专门用于存储和检索向量嵌入的数据库系统...|document.pdf"
     * </pre>
     *
     * <p>注意：这种基于文本前缀的 Key 生成方式简单高效，但存在一个理论上的边界情况：
     * 如果两个不同文档的前 100 个字符完全相同且 source 也相同，会被误判为同一文档。
     * 在实际业务中，这种情况极其罕见，可以接受。</p>
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

    /**
     * <h3>从 docId 中提取文档来源标识</h3>
     *
     * <p>docId 的命名规则为：{@code "documentName_chunkIndex"}，例如：</p>
     * <ul>
     *   <li>{@code "章程.pdf_0"} → 提取为 {@code "章程.pdf"}</li>
     *   <li>{@code "技术文档_v2_5"} → 提取为 {@code "技术文档_v2"}（取最后一个下划线之前的部分）</li>
     * </ul>
     *
     * <p>提取逻辑：<b>取最后一个下划线之前的所有字符</b>。
     * 如果 docId 中没有下划线，则返回原始 docId。</p>
     *
     * @param docId 文档唯一标识，格式为 "source_chunkIndex"
     * @return 提取出的文档来源标识
     */
    private String extractSourceFromDocId(String docId) {
        // 找到最后一个下划线的位置
        int lastUnderscore = docId.lastIndexOf("_");
        // 如果存在下划线，截取之前的部分；否则返回原始 docId
        return lastUnderscore > 0 ? docId.substring(0, lastUnderscore) : docId;
    }

    // ==================== 内部类 ====================

    /**
     * <h3>排序结果包装类</h3>
     *
     * <p>内部使用的数据结构，将文本片段与其检索分数绑定在一起。</p>
     *
     * <p>分数含义取决于检索路径：</p>
     * <ul>
     *   <li>向量检索路径：score 为余弦相似度（0.0 ~ 1.0）</li>
     *   <li>关键词检索路径：score 为 BM25 分数（无上限）</li>
     *   <li>RRF 融合后：score 为 RRF 累加分数</li>
     * </ul>
     */
    private static class RankedResult {
        /** 文本片段，包含文档内容和元数据 */
        private final TextSegment segment;
        /** 检索分数（向量相似度 / BM25分数 / RRF融合分数） */
        private final double score;

        RankedResult(TextSegment segment, double score) {
            this.segment = segment;
            this.score = score;
        }

        TextSegment getSegment() { return segment; }
        double getScore() { return score; }
    }
}