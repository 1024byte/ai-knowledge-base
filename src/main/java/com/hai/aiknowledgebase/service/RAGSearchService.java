package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.SearchResult;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.ContainsString;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
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
 *     ├── 阶段2：扩展关键词辅助检索 ── 对每个扩展词独立做向量检索，去重追加
 *     │
 *     ├── 阶段3：文件名过滤检索 ── 检测查询中的文件名，在指定文件范围内检索
 *     │
 *     └── 阶段4：排除关键词过滤 ── 剔除包含排除词的片段（如 "不要XX"）
 *     │
 *     └──→ 最终去重结果集
 * </pre>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>去重策略</b>：基于文本内容完全匹配（{@code text.equals()}），确保同一片段不会被重复返回</li>
 *   <li><b>文件名提取</b>：三层正则匹配（精确扩展名 → 动作模式 → 位置模式），覆盖多种表达方式</li>
 *   <li><b>降级机制</b>：带扩展名过滤失败时，自动尝试去除扩展名重试</li>
 * </ul>
 *
 * @see HybridSearchService 混合检索服务（向量 + BM25）
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

    /** 默认检索返回数量（无过滤时） */
    private static final int DEFAULT_MAX_RESULTS = 5;

    /** 文件名过滤模式下的检索返回数量（扩大范围以提高命中率） */
    private static final int MAX_RESULTS_WHEN_FILTERED = 20;

    // ==================== 核心检索方法 ====================

    /**
     * <h3>检索文档片段（简化版）</h3>
     *
     * <p>不传入扩展词和排除词，仅执行混合检索 + 文件名过滤检索。</p>
     *
     * @param userQuery 用户原始查询
     * @param topK      返回结果数量上限
     * @return 检索到的文档片段列表
     */
    public List<TextSegment> retrieveSegments(String userQuery, int topK) {
        return retrieveSegments(userQuery, topK, null, null);
    }

    /**
     * <h3>检索文档片段（完整版）—— 四阶段级联检索</h3>
     *
     * <p>这是 RAG 检索的核心编排方法，按以下四个阶段依次执行：</p>
     *
     * <h4>阶段1：混合检索（主路径）</h4>
     * <p>调用 {@link HybridSearchService#hybridSearch} 并行执行向量检索和 BM25 关键词检索，
     * 使用 RRF 算法融合排序。这是整个检索流程的基石，提供了语义相关性和关键词精确性的双重要求。</p>
     *
     * <h4>阶段2：扩展关键词辅助检索</h4>
     * <p>对每个扩展关键词（来自 QueryRewriteService 的 L1 同义词扩展 / L2 意图扩展），
     * 独立执行一次向量检索，将结果去重后追加到结果集中。</p>
     * <p>为什么用向量检索而不是混合检索？扩展词通常较短，BM25 效果有限，向量检索的语义泛化能力更强。</p>
     * <p>检索数量 = max(topK/2, 3)，保证每个扩展词至少取 3 个结果，但不超过 topK 的一半。</p>
     *
     * <h4>阶段3：文件名过滤检索</h4>
     * <p>检测用户查询中是否包含文件名（如 "章程.pdf"），如果包含，则在指定文件的范围内
     * 进行向量检索。这对于用户明确指定查看某个文档的场景非常有效。</p>
     * <p>降级机制：如果带扩展名过滤无结果，自动尝试去除扩展名（如 "章程"）重试。</p>
     *
     * <h4>阶段4：排除关键词过滤</h4>
     * <p>遍历所有结果片段，如果片段文本中包含排除关键词（如用户说 "不包含XX"），
     * 则将该片段从结果集中移除。这是对检索结果的二次过滤，确保不返回用户明确不要的内容。</p>
     *
     * @param userQuery       用户原始查询
     * @param topK            返回结果数量上限
     * @param expandKeywords  扩展关键词列表（来自查询改写服务）
     * @param excludeKeywords 排除关键词列表（来自查询改写服务）
     * @return 去重、过滤后的文档片段列表
     */
    public List<TextSegment> retrieveSegments(String userQuery, int topK,
                                              List<String> expandKeywords,
                                              List<String> excludeKeywords) {
        log.info("开始检索，用户查询: {} | 扩展词: {} | 排除词: {}",
                userQuery, expandKeywords, excludeKeywords);

        // ===== 阶段1：混合检索（主路径）=====
        // 并行执行向量检索 + BM25 关键词检索，RRF 融合排序
        List<TextSegment> allResults = new ArrayList<>(hybridSearchService.hybridSearch(userQuery, topK));
        log.info("混合检索召回 {} 个片段", allResults.size());

        // ===== 阶段2：扩展关键词辅助检索 =====
        if (expandKeywords != null && !expandKeywords.isEmpty()) {
            for (String keyword : expandKeywords) {
                // 跳过与原始查询完全相同的扩展词，避免重复检索
                if (keyword.equals(userQuery)) continue;

                // 对每个扩展词独立执行向量检索
                // 检索数量 = max(topK/2, 3)，保证至少取 3 个结果
                List<TextSegment> expanded = hybridSearchService.vectorSearch(
                        keyword, Math.max(topK / 2, 3));

                // 去重：基于文本内容完全匹配，避免同一片段被重复添加
                for (TextSegment seg : expanded) {
                    if (allResults.stream().noneMatch(
                            existing -> existing.text().equals(seg.text()))) {
                        allResults.add(seg);
                    }
                }
            }
            log.info("扩展关键词检索后总片段数: {}", allResults.size());
        }

        // ===== 阶段3：文件名过滤检索 =====
        // 检测用户查询中是否包含文件名（如 "章程.pdf"、"打开文档里的内容"）
        String fileNameKeyword = extractFileName(userQuery);
        if (fileNameKeyword != null) {
            log.info("检测到文件名关键词: {}，执行辅助过滤检索", fileNameKeyword);

            // 从原始查询中移除文件名，提取纯内容查询
            String contentQuery = extractContentQuery(userQuery, fileNameKeyword);

            // 在指定文件范围内进行向量检索
            List<TextSegment> filtered = searchWithFileNameFilter(
                    fileNameKeyword, contentQuery, userQuery, MAX_RESULTS_WHEN_FILTERED);

            // 去重追加
            for (TextSegment seg : filtered) {
                if (allResults.stream().noneMatch(
                        existing -> existing.text().equals(seg.text()))) {
                    allResults.add(seg);
                }
            }

            // 降级机制：如果带扩展名过滤无结果，尝试去除扩展名重试
            // 例如：用户输入 "章程.pdf"，但数据库中 source 字段存的是 "章程"
            if (filtered.isEmpty()) {
                String noExt = removeExtension(fileNameKeyword);
                if (!noExt.equals(fileNameKeyword)) {
                    log.info("尝试不带扩展名检索: {}", noExt);
                    List<TextSegment> retry = searchWithFileNameFilter(
                            noExt, contentQuery, userQuery, MAX_RESULTS_WHEN_FILTERED);
                    for (TextSegment seg : retry) {
                        if (allResults.stream().noneMatch(
                                existing -> existing.text().equals(seg.text()))) {
                            allResults.add(seg);
                        }
                    }
                }
            }
        }

        // ===== 阶段4：排除关键词过滤 =====
        // 遍历结果集，剔除包含排除关键词的片段
        if (excludeKeywords != null && !excludeKeywords.isEmpty()) {
            int beforeFilter = allResults.size();
            allResults = allResults.stream()
                    .filter(seg -> {
                        String text = seg.text();
                        // 如果片段文本中包含任一排除关键词，则过滤掉
                        for (String exclude : excludeKeywords) {
                            if (text.contains(exclude)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
            log.info("排除关键词过滤: {} -> {} 个片段", beforeFilter, allResults.size());
        }

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

    /**
     * <h3>带文件名过滤的向量检索</h3>
     *
     * <p>在向量检索的基础上，增加 source 字段的过滤条件，仅检索指定文件范围内的文档片段。</p>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>ContainsString("source", fileNameKeyword)</b>：
     *       LangChain4j 的元数据过滤器，表示 source 字段必须包含 fileNameKeyword。
     *       PGVector 底层会将其转换为 SQL WHERE 条件：{@code metadata->>'source' LIKE '%keyword%'}</li>
     *   <li><b>searchQuery 的选择逻辑</b>：
     *       如果移除文件名后仍有内容查询（如 "打开章程.pdf 里的配置说明" → "配置说明"），
     *       则使用内容查询；否则回退到原始查询。这保证了即使在文件名过滤模式下，
     *       也能精确匹配用户的内容意图。</li>
     * </ul>
     *
     * @param fileNameKeyword 文件名关键词（用于 source 字段过滤）
     * @param contentQuery    移除文件名后的纯内容查询
     * @param originalQuery   原始查询（当 contentQuery 为空时使用）
     * @param maxResults      最大返回数量
     * @return 在指定文件范围内检索到的文档片段列表
     */
    private List<TextSegment> searchWithFileNameFilter(String fileNameKeyword,
                                                        String contentQuery,
                                                        String originalQuery,
                                                        int maxResults) {
        // 构建元数据过滤器：source 字段必须包含 fileNameKeyword
        Filter filter = new ContainsString("source", fileNameKeyword);

        // 选择检索查询：优先使用纯内容查询，为空时回退到原始查询
        String searchQuery = contentQuery.trim().isEmpty() ? originalQuery : contentQuery;

        // 构建带过滤条件的向量检索请求
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(searchQuery).content())
                .maxResults(maxResults)
                .filter(filter)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();
        log.info("文件名过滤检索（{}）召回 {} 个片段", fileNameKeyword, matches.size());

        return matches.stream().map(EmbeddingMatch::embedded).collect(Collectors.toList());
    }

    // ==================== 文件名提取与处理 ====================

    /**
     * <h3>从用户查询中提取文件名</h3>
     *
     * <p>使用三层正则表达式级联匹配，按优先级从高到低依次尝试：</p>
     *
     * <h4>第一层：精确扩展名匹配（优先级最高）</h4>
     * <pre>
     * 正则: ([\w\-\u4e00-\u9fa5]+\.(md|pdf|docx|txt|pptx|xlsx|doc|ppt|xls))
     *
     * 示例匹配:
     *   "打开 章程.pdf"       → "章程.pdf"
     *   "查看 README.md"      → "README.md"
     *   "搜索 技术文档.docx"  → "技术文档.docx"
     * </pre>
     * <p>这是最精确的匹配方式，直接匹配带扩展名的文件名。</p>
     *
     * <h4>第二层：动作模式匹配</h4>
     * <pre>
     * 正则: (?:只看|打开|查看|搜索)\s*([\u4e00-\u9fa5\w\-]+?)\s*(?:里的|中的|文档|文件|$)
     *
     * 示例匹配:
     *   "打开 章程 里的"      → "章程"
     *   "查看 技术文档 文档"  → "技术文档"
     *   "只看 产品手册"       → "产品手册"
     * </pre>
     * <p>匹配用户使用动作动词指定文档的场景，如"打开XX"、"查看XX"。</p>
     *
     * <h4>第三层：位置模式匹配</h4>
     * <pre>
     * 正则: ([\u4e00-\u9fa5\w\-]+?)\s*(?:里|中)\s*的
     *
     * 示例匹配:
     *   "章程 里的 配置"     → "章程"
     *   "文档 中的 说明"     → "文档"
     * </pre>
     * <p>匹配用户使用位置描述指定文档的场景，如"XX里的"、"XX中的"。需要至少 2 个字符。</p>
     *
     * @param query 用户查询文本
     * @return 提取到的文件名，未匹配到返回 null
     */
    private String extractFileName(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        String trimmed = query.trim();

        // 第一层：精确扩展名匹配
        // 匹配 中文/英文/数字/连字符 + .扩展名（md/pdf/docx/txt/pptx/xlsx/doc/ppt/xls）
        Pattern exactFileNamePattern = Pattern.compile(
                "([\\w\\-\\u4e00-\\u9fa5]+\\.(md|pdf|docx|txt|pptx|xlsx|doc|ppt|xls))");
        var exactMatcher = exactFileNamePattern.matcher(trimmed);
        if (exactMatcher.find()) {
            String fileName = exactMatcher.group(1);
            log.debug("精确匹配到文件名: {}", fileName);
            return fileName;
        }

        // 第二层：动作模式匹配
        // 匹配 "打开/查看/只看/搜索" + 文件名 + "里的/中的/文档/文件"
        Pattern actionPattern = Pattern.compile(
                "(?:只看|打开|查看|搜索)\\s*([\\u4e00-\\u9fa5\\w\\-]+?)\\s*(?:里的|中的|文档|文件|$)");
        var actionMatcher = actionPattern.matcher(trimmed);
        if (actionMatcher.find()) {
            String fileName = actionMatcher.group(1);
            log.debug("动作模式文件名匹配: {}", fileName);
            return fileName;
        }

        // 第三层：位置模式匹配
        // 匹配 "文件名" + "里/中" + "的"
        Pattern locationPattern = Pattern.compile(
                "([\\u4e00-\\u9fa5\\w\\-]+?)\\s*(?:里|中)\\s*的");
        var locationMatcher = locationPattern.matcher(trimmed);
        if (locationMatcher.find()) {
            String fileName = locationMatcher.group(1);
            // 文件名至少 2 个字符，避免匹配到 "里的" 中的 "里"
            if (fileName.length() >= 2) {
                log.debug("位置模式文件名匹配: {}", fileName);
                return fileName;
            }
        }

        return null;
    }

    /**
     * <h3>从原始查询中提取纯内容查询</h3>
     *
     * <p>移除文件名关键词和动作/位置描述词，剩余部分即为用户的纯内容查询意图。</p>
     *
     * <h4>示例</h4>
     * <pre>
     * "打开 章程.pdf 里的 配置说明" → "配置说明"
     * "查看 技术文档 中的 部署流程" → "部署流程"
     * </pre>
     *
     * <p>移除的词包括：文件名本身 + "只看/打开/查看/搜索/里的/中的/文档/文件"等描述词。</p>
     *
     * @param originalQuery   原始查询文本
     * @param fileNameKeyword 已提取到的文件名关键词
     * @return 移除文件名和描述词后的纯内容查询
     */
    private String extractContentQuery(String originalQuery, String fileNameKeyword) {
        // 第一步：移除文件名关键词
        String contentQuery = originalQuery.replace(fileNameKeyword, "").trim();
        // 第二步：移除动作/位置描述词
        contentQuery = contentQuery.replaceAll(
                "(?:只看|打开|查看|搜索|里的|中的|文档|文件)", "").trim();
        return contentQuery;
    }

    /**
     * <h3>移除文件扩展名</h3>
     *
     * <p>取最后一个点号之前的部分，用于文件名过滤的降级重试。</p>
     *
     * <h4>示例</h4>
     * <pre>
     * "章程.pdf"     → "章程"
     * "技术文档.docx" → "技术文档"
     * "README"       → "README"  （无扩展名，原样返回）
     * </pre>
     *
     * @param fileName 带扩展名的文件名
     * @return 移除扩展名后的文件名
     */
    private String removeExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
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
     *   <li>调用 {@link #retrieveSegments} 执行四阶段级联检索</li>
     *   <li>如果检索结果为空，返回兜底提示语</li>
     *   <li>提取文件名（用于上下文来源声明）</li>
     *   <li>构建格式化上下文</li>
     *   <li>调用 LLM 生成最终答案</li>
     * </ol>
     *
     * @param userQuery 用户原始查询
     * @return LLM 生成的答案，或未找到信息时的兜底提示
     */
    public String searchAndAnswer(String userQuery) {
        // 步骤1：执行四阶段级联检索
        List<TextSegment> segments = retrieveSegments(userQuery, DEFAULT_MAX_RESULTS);

        // 步骤2：检索结果为空时返回兜底提示
        if (segments.isEmpty()) {
            return "未找到相关信息，请尝试更换关键词或指定文件名进行搜索。";
        }

        // 步骤3：提取文件名，用于上下文来源标注
        String fileName = extractFileName(userQuery);

        // 步骤4：构建格式化上下文
        String context = buildContext(segments, fileName);

        // 步骤5：调用 LLM 生成答案
        return generateAnswer(userQuery, context, fileName != null);
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
        List<TextSegment> segments = retrieveSegments(query, topK);

        // 将 TextSegment 转为 SearchResult DTO
        return segments.stream()
                .map(seg -> {
                    // 从 metadata 中提取 source 字段作为文档来源
                    String source = seg.metadata().getString("source");
                    return new SearchResult(
                            seg.text(),                                          // 片段文本
                            0.0,                                                 // 分数（混合检索场景下无统一分数，置 0）
                            source != null ? source : "未知来源"                  // 文档来源
                    );
                })
                .collect(Collectors.toList());
    }
}