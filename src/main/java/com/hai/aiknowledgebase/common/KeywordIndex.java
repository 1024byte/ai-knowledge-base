package com.hai.aiknowledgebase.common;

import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * <h2>BM25 关键词倒排索引</h2>
 *
 * <p>实现基于 BM25 算法的全文检索索引，用于混合检索中的关键词精确匹配路径。</p>
 *
 * <h3>核心数据结构</h3>
 * <pre>
 * docs (ConcurrentHashMap)
 *   ┌──────────┬──────────────────────────────────────────┐
 *   │ docId    │ 原始文本（用于检索时返回文档内容）          │
 *   │ "42_0"   │ "## 第一章\n\n这是文档内容..."              │
 *   │ "42_1"   │ "## 第二章\n\n继续..."                    │
 *   └──────────┴──────────────────────────────────────────┘
 *
 * invertedIndex (ConcurrentHashMap)
 *   ┌──────────┬──────────────────────────────────────────┐
 *   │ term     │ postings (docId → term frequency)         │
 *   │ "配置"   │ {"42_0": 3, "42_1": 1, "55_0": 2}        │
 *   │ "API"    │ {"42_0": 5, "55_0": 1}                   │
 *   └──────────┴──────────────────────────────────────────┘
 * </pre>
 *
 * <h3>BM25 算法公式</h3>
 * <pre>
 * IDF(q) = log(1 + (N - df + 0.5) / (df + 0.5))
 *
 * BM25(d, q) = IDF(q) × (tf × (k1 + 1)) / (tf + k1 × (1 - b + b × |d|/avgdl))
 *
 * 其中：
 *   N      = 文档总数
 *   df     = 包含该词的文档数（文档频率）
 *   tf     = 词在文档中的出现次数（词频）
 *   |d|    = 文档长度（字符数）
 *   avgdl  = 平均文档长度
 *   k1=1.2 = 词频饱和度参数（控制 tf 的影响）
 *   b=0.75 = 长度归一化参数（控制文档长度惩罚）
 * </pre>
 *
 * <h3>分词策略</h3>
 * <ul>
 *   <li><b>中文文本</b>：通过 {@link ChineseTokenizerService}（基于 jieba 搜索引擎模式）
 *       进行细粒度分词，过滤停用词和单字</li>
 *   <li><b>非中文文本</b>：按空白字符和标点符号分割，统一转小写</li>
 *   <li>通过 {@link #containsChinese} 自动检测文本语言，选择对应策略</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>使用 {@link ConcurrentHashMap} 存储 docs 和 invertedIndex，
 * 支持多线程并发读写。{@code avgDocLength} 使用 {@code volatile} 保证可见性。</p>
 *
 * @see ChineseTokenizerService 中文分词服务（基于 jieba）
 * @see com.hai.aiknowledgebase.service.HybridSearchService 混合检索服务（调用方）
 */
@Slf4j
public class KeywordIndex {

    /**
     * 文档存储：docId → 原始文本。
     * 用于检索时返回文档内容，以及计算文档长度。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, String> docs = new ConcurrentHashMap<>();

    /**
     * 倒排索引：term → (docId → 词频)。
     * 外层 Map 的 Key 是分词后的词条，Value 是该词条在哪些文档中出现及出现次数。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, Map<String, Integer>> invertedIndex = new ConcurrentHashMap<>();

    /**
     * 平均文档长度（字符数）。
     * 用于 BM25 算法的长度归一化。使用 volatile 保证多线程可见性。
     * 值在每次索引操作（index/remove）后自动更新。
     */
    private volatile double avgDocLength = 1.0;

    /** 中文分词服务，基于 jieba 搜索引擎模式 */
    private final ChineseTokenizerService tokenizerService;

    /**
     * 构造函数，注入中文分词服务。
     * 由 {@link com.hai.aiknowledgebase.service.HybridSearchService} 在初始化时创建。
     *
     * @param tokenizerService 中文分词服务实例
     */
    public KeywordIndex(ChineseTokenizerService tokenizerService) {
        this.tokenizerService = tokenizerService;
    }

    // ======================== 索引写入 ========================

    /**
     * <h3>索引单个文档</h3>
     *
     * <p>将文档加入倒排索引。如果该 docId 已存在，先移除旧索引再建立新索引（覆盖更新）。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>将文档文本存入 docs Map</li>
     *   <li>如果 docId 已存在，先调用 {@link #removeFromIndex} 移除旧索引</li>
     *   <li>调用 {@link #analyze} 对文本进行分词，得到词频 Map</li>
     *   <li>遍历每个词条，更新倒排索引：{@code invertedIndex[term][docId] = tf}</li>
     *   <li>重新计算平均文档长度</li>
     * </ol>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code docs.put(docId, text)}</b>：ConcurrentHashMap 的原子操作，
     *       返回旧值（如果有）。通过返回值判断是否需要移除旧索引。</li>
     *   <li><b>{@code invertedIndex.computeIfAbsent(term, k -> new ConcurrentHashMap<>())}</b>：
     *       如果词条首次出现，创建新的 ConcurrentHashMap 作为 postings 列表。
     *       然后直接 put docId → tf 的映射。</li>
     * </ul>
     *
     * @param docId 文档唯一标识（如 "42_0" 表示文档 42 的第 0 个片段）
     * @param text  文档文本内容
     */
    public void index(String docId, String text) {
        // 存入文档，如果已存在则获取旧文本用于清理旧索引
        String oldText = docs.put(docId, text);
        if (oldText != null) {
            // 覆盖更新：先移除旧索引
            removeFromIndex(docId, oldText);
        }

        // 分词并统计词频
        Map<String, Integer> termFreqs = analyze(text);

        // 更新倒排索引：每个词条 → (docId → 词频)
        for (var entry : termFreqs.entrySet()) {
            invertedIndex
                    .computeIfAbsent(entry.getKey(), k -> new ConcurrentHashMap<>())
                    .put(docId, entry.getValue());
        }

        // 重新计算平均文档长度
        avgDocLength = docs.values().stream()
                .mapToInt(String::length)
                .average()
                .orElse(1.0);
    }

    /**
     * <h3>批量索引文档</h3>
     *
     * <p>遍历 Map 中的每个文档，逐个调用 {@link #index} 进行索引。
     * 适用于初始化时批量加载文档。</p>
     *
     * @param documents docId → 文本的映射
     */
    public void indexAll(Map<String, String> documents) {
        for (var entry : documents.entrySet()) {
            index(entry.getKey(), entry.getValue());
        }
    }

    // ======================== 索引删除 ========================

    /**
     * <h3>删除单个文档的索引</h3>
     *
     * <p>从 docs 和倒排索引中移除该文档。同时清理空的 postings 列表。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>从 docs 中移除文档，获取原始文本</li>
     *   <li>调用 {@link #removeFromIndex} 从倒排索引中移除该文档的所有词条</li>
     *   <li>重新计算平均文档长度</li>
     * </ol>
     *
     * @param docId 要删除的文档 ID
     */
    public void remove(String docId) {
        String text = docs.remove(docId);
        if (text != null) {
            removeFromIndex(docId, text);
            avgDocLength = docs.values().stream()
                    .mapToInt(String::length)
                    .average()
                    .orElse(1.0);
        }
    }

    /**
     * <h3>按前缀批量删除文档</h3>
     *
     * <p>删除所有 docId 以指定前缀开头的文档。
     * 典型用法：删除文档时，所有 chunkId 为 "docId_" 格式，
     * 使用 {@code removeByPrefix(docId + "_")} 即可一次性清理该文档的所有片段。</p>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code docs.keySet().stream().filter(id -> id.startsWith(prefix))}</b>：
     *       先收集所有匹配的 docId，再逐个删除。避免在遍历过程中直接修改 Map。</li>
     * </ul>
     *
     * @param prefix 文档 ID 前缀（如 "42_"）
     */
    public void removeByPrefix(String prefix) {
        // 先收集匹配的 docId，避免在遍历时修改 Map
        List<String> toRemove = docs.keySet().stream()
                .filter(id -> id.startsWith(prefix))
                .collect(Collectors.toList());
        for (String docId : toRemove) {
            remove(docId);
        }
        if (!toRemove.isEmpty()) {
            log.info("Removed {} docs with prefix {}", toRemove.size(), prefix);
        }
    }

    // ======================== 检索 ========================

    /**
     * <h3>BM25 关键词检索</h3>
     *
     * <p>对查询文本进行分词，然后对每个词条在倒排索引中查找匹配文档，
     * 使用 BM25 算法计算相关性得分，返回 Top-K 结果。</p>
     *
     * <h4>BM25 得分计算流程</h4>
     * <ol>
     *   <li>对查询文本分词，得到查询词条及词频</li>
     *   <li>对每个查询词条：
     *     <ul>
     *       <li>从倒排索引中获取该词条的 postings 列表</li>
     *       <li>计算 IDF（逆文档频率）：IDF = log(1 + (N - df + 0.5) / (df + 0.5))</li>
     *       <li>对 postings 中的每个文档，计算 BM25 得分</li>
     *     </ul>
     *   </li>
     *   <li>按得分降序排列，取 Top-K</li>
     * </ol>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code idf = Math.log(1 + (N - df + 0.5) / (df + 0.5))}</b>：
     *       BM25 标准 IDF 公式。加 1 确保 IDF 始终为正（即使 df=N），
     *       加 0.5 平滑处理避免除零。</li>
     *   <li><b>{@code (tf × (k1+1)) / (tf + k1 × (1 - b + b × docLen/avgDocLength))}</b>：
     *       BM25 词频饱和度公式。k1=1.2 控制 tf 增长趋于饱和的速度，
     *       b=0.75 控制文档长度惩罚的力度。</li>
     *   <li><b>{@code scores.merge(docId, score × qtf, Double::sum)}</b>：
     *       累加同一文档中不同查询词条的得分。qtf 是查询词频，用于加权。</li>
     * </ul>
     *
     * @param query 查询文本
     * @param topK  返回前 K 个结果
     * @return 按得分降序排列的 (docId, score) 列表
     */
    public List<Map.Entry<String, Double>> search(String query, int topK) {
        // 对查询文本分词
        Map<String, Integer> queryTerms = analyze(query);
        if (queryTerms.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Double> scores = new HashMap<>();
        int totalDocs = docs.size();
        if (totalDocs == 0) {
            return Collections.emptyList();
        }

        // 遍历每个查询词条
        for (var queryEntry : queryTerms.entrySet()) {
            String term = queryEntry.getKey();
            int qtf = queryEntry.getValue();  // 查询词频

            // 从倒排索引中获取该词条的 postings 列表
            Map<String, Integer> postings = invertedIndex.get(term);
            if (postings == null) continue;  // 该词条不在任何文档中

            int df = postings.size();  // 文档频率

            // BM25 IDF 计算
            double idf = Math.log(1 + (totalDocs - df + 0.5) / (df + 0.5));

            // 对每个包含该词条的文档计算 BM25 得分
            for (var docEntry : postings.entrySet()) {
                String docId = docEntry.getKey();
                int tf = docEntry.getValue();  // 词频
                int docLen = docs.get(docId).length();  // 文档长度

                // BM25 参数
                double k1 = 1.2;  // 词频饱和度
                double b = 0.75;  // 长度归一化

                // BM25 得分计算
                double score = idf * (tf * (k1 + 1))
                        / (tf + k1 * (1 - b + b * docLen / avgDocLength));

                // 累加得分（同一文档中不同查询词条的得分求和）
                scores.merge(docId, score * qtf, Double::sum);
            }
        }

        // 按得分降序排列，取 Top-K
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    // ======================== 查询方法 ========================

    /**
     * <h3>获取文档原始文本</h3>
     *
     * @param docId 文档 ID
     * @return 文档原始文本，不存在返回 null
     */
    public String getDocText(String docId) {
        return docs.get(docId);
    }

    /**
     * <h3>获取已索引的文档总数</h3>
     *
     * @return 文档数量
     */
    public int size() {
        return docs.size();
    }

    /**
     * <h3>清空所有索引数据</h3>
     *
     * <p>清空 docs、倒排索引，重置平均文档长度。
     * 注意：此操作不可逆，谨慎使用。</p>
     */
    public void clear() {
        docs.clear();
        invertedIndex.clear();
        avgDocLength = 1.0;
    }

    // ======================== 内部分词 ========================

    /**
     * <h3>文本分词 + 词频统计</h3>
     *
     * <p>自动检测文本语言，选择对应的分词策略：</p>
     * <ul>
     *   <li><b>含中文</b>：使用 {@link ChineseTokenizerService#tokenize}（jieba 搜索引擎模式），
     *       自动过滤停用词和单字</li>
     *   <li><b>纯英文/数字</b>：按空白字符和标点符号分割，统一转小写</li>
     * </ul>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code termFreq.merge(token, 1, Integer::sum)}</b>：
     *       Map 的 merge 操作，如果 key 不存在则设值为 1，如果已存在则累加。
     *       等价于 {@code termFreq.put(token, termFreq.getOrDefault(token, 0) + 1)}。</li>
     *   <li><b>{@code text.split("[\\s\\p{Punct}]+")}</b>：
     *       正则分割：{@code \s} 匹配空白字符，{@code \p{Punct}} 匹配标点符号。
     *       用于非中文文本的简单分词。</li>
     * </ul>
     *
     * @param text 待分词文本
     * @return term → 词频的映射
     */
    private Map<String, Integer> analyze(String text) {
        Map<String, Integer> termFreq = new HashMap<>();

        if (tokenizerService != null && containsChinese(text)) {
            // 中文文本：使用 jieba 搜索引擎模式分词
            List<String> tokens = tokenizerService.tokenize(text, true);
            for (String token : tokens) {
                if (token.trim().isEmpty()) continue;
                termFreq.merge(token, 1, Integer::sum);
            }
        } else {
            // 非中文文本：按空白字符和标点符号分割
            for (String token : text.split("[\\s\\p{Punct}]+")) {
                token = token.toLowerCase().trim();
                if (!token.isEmpty()) {
                    termFreq.merge(token, 1, Integer::sum);
                }
            }
        }
        return termFreq;
    }

    /**
     * <h3>检测文本是否包含中文字符</h3>
     *
     * <p>遍历文本中的每个字符，检查是否在 Unicode 中文范围（0x4E00 - 0x9FFF）内。</p>
     *
     * <h4>Unicode 中文范围</h4>
     * <ul>
     *   <li>0x4E00 - 0x9FFF：CJK 统一表意文字（常用汉字）</li>
     * </ul>
     *
     * @param text 待检测文本
     * @return true 包含中文字符，false 不包含
     */
    private boolean containsChinese(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) return true;
        }
        return false;
    }

    /**
     * <h3>从倒排索引中移除指定文档的所有词条</h3>
     *
     * <p>对文档文本进行分词，然后从倒排索引中移除该文档在每个词条下的记录。
     * 如果某词条的 postings 列表变空，则删除该词条。</p>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code postings.remove(docId)}</b>：从该词条的 postings 中移除指定文档</li>
     *   <li><b>{@code postings.isEmpty() → invertedIndex.remove(term)}</b>：
     *       如果 postings 变空，说明该词条不再出现在任何文档中，从倒排索引中完全删除。
     *       避免索引膨胀，节省内存。</li>
     * </ul>
     *
     * @param docId 要移除的文档 ID
     * @param text  文档原始文本（用于分词）
     */
    private void removeFromIndex(String docId, String text) {
        Map<String, Integer> terms = analyze(text);
        for (String term : terms.keySet()) {
            Map<String, Integer> postings = invertedIndex.get(term);
            if (postings != null) {
                postings.remove(docId);
                // 如果该词条不再出现在任何文档中，删除整个词条
                if (postings.isEmpty()) {
                    invertedIndex.remove(term);
                }
            }
        }
    }
}