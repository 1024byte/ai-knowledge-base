package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.common.ContentAnalyzer;
import com.hai.aiknowledgebase.common.ContentCategory;
import com.hai.aiknowledgebase.common.CustomDocument;
import com.hai.aiknowledgebase.config.ChunkingConfig;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>文档智能路由器</h2>
 *
 * <p>根据文档内容和格式，自动选择最优的切分策略。核心思想是<b>内容感知 + 策略路由</b>：</p>
 *
 * <pre>
 * 输入文档
 *     │
 *     ├── 格式判断：Excel/CSV？ → 是 → 按行切分（特殊通道）
 *     │                          → 否 → 继续
 *     │
 *     ├── 内容分析：ContentAnalyzer.analyze()
 *     │      ├── 技术文档 (TECHNICAL)     → minTokens=200, maxTokens=1000, 关语义
 *     │      ├── 法律文档 (LEGAL)         → minTokens=300, maxTokens=1200, 开语义 + 大重叠
 *     │      ├── 表格密集 (TABLE_HEAVY)   → minTokens=100, maxTokens=800, 关语义
 *     │      └── 通用文档 (GENERAL)       → minTokens=100, maxTokens=512, 关语义
 *     │
 *     └── 执行切分：获取/创建 Chunker → MarkdownDocumentChunker.chunk()
 * </pre>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>Chunker 实例缓存</b>：以配置参数组合为 Key，缓存已创建的 {@link MarkdownDocumentChunker} 实例。
 *       避免为每个文档重复创建 Chunker，减少 GC 压力。</li>
 *   <li><b>内容自动分类</b>：通过 {@link ContentAnalyzer} 对文本前 3000 字符采样分析，
 *       识别技术文档、法律文档、表格密集文档和通用文档四类，自动匹配最优切分配置。</li>
 *   <li><b>仪器化降级</b>：表格式特殊通道失败或内容不足时，自动降级到 GENERAL 通用配置。</li>
 *   <li><b>语义开关</b>：法律文档开启语义切分（因为法律文本通常无标题结构），
 *       技术文档关闭语义切分（依赖代码块和标题结构），避免不必要的向量计算开销。</li>
 * </ul>
 *
 * <h3>评分决策机制（ContentAnalyzer）</h3>
 * <table>
 *   <tr><th>维度</th><th>强特征（+3分）</th><th>弱特征（+1分）</th></tr>
 *   <tr><td>技术</td><td>代码块 ```</td><td>行内代码 `</td></tr>
 *   <tr><td>法律</td><td>第X条、依据《</td><td>甲方、乙方、违约、赔偿</td></tr>
 *   <tr><td>表格</td><td>表格行 &gt;10</td><td>表格行占比 &gt;20%</td></tr>
 * </table>
 * <p>决策保护：得分都 &lt;2 → GENERAL；最高分与次高分差 &lt;1.5 且最高分 &lt;6 → GENERAL。</p>
 *
 * @see ContentAnalyzer 内容分析器
 * @see ChunkingConfig 切分配置（含4种预设）
 * @see MarkdownDocumentChunker 文档切片器
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class DocumentRouter {

    /** Token 估算器，用于计算文本 token 数和校验 Chunk 大小 */
    private final TokenCountEstimator tokenEstimator;

    /**
     * 嵌入模型，用于语义切分场景。
     * 当 {@link ChunkingConfig#isEnableSemantic()} 为 true 时传给 Chunker，
     * 否则传 null 避免不必要的向量计算。
     */
    private final EmbeddingModel embeddingModel;

    /** 默认语义阈值，当 ChunkingConfig 未指定时使用 */
    private final double defaultSemanticThreshold = 0.75;

    /**
     * Chunker 实例缓存。
     * Key 为配置参数的组合字符串（如 "200_1000_0.10_false_0.70"），
     * Value 为对应的 MarkdownDocumentChunker 实例。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, MarkdownDocumentChunker> chunkerCache = new ConcurrentHashMap<>();

    /**
     * 内容分析器实例。
     * 直接在字段中初始化（而非通过 Spring 注入），因为 ContentAnalyzer 是无状态的工具类，
     * 不需要 Spring 管理生命周期。
     */
    private final ContentAnalyzer contentAnalyzer = new ContentAnalyzer();

    // ======================== 路由入口 ========================

    /**
     * <h3>路由入口：根据文档内容和格式，选择最优切分策略</h3>
     *
     * <p>这是 DocumentRouter 的唯一对外暴露方法。调用方（DocumentChunkerService）只需传入
     * 统一文档对象，路由逻辑内部自动完成格式判断、内容分析、配置选择和切分执行。</p>
     *
     * <h4>执行流程（六步）</h4>
     * <ol>
     *   <li><b>格式特殊通道</b>：Excel/CSV 格式 → 跳过内容分析，直接走按行切分</li>
     *   <li><b>提取文本内容</b>：从 CustomDocument 中获取纯文本，空内容直接返回空列表</li>
     *   <li><b>内容特征分析</b>：调用 {@link ContentAnalyzer#analyze} 对前 3000 字符采样，
     *       输出内容分类（TECHNICAL / LEGAL / TABLE_HEAVY / GENERAL）</li>
     *   <li><b>获取切分配置</b>：根据分类匹配 {@link ChunkingConfig} 预设配置</li>
     *   <li><b>获取/创建 Chunker</b>：以配置参数为 Key 从缓存中获取，
     *       不存在则创建新实例并缓存。注意：如果配置中 enableSemantic=false，
     *       传入 null 作为 EmbeddingModel，避免无意义的向量计算</li>
     *   <li><b>执行切分</b>：调用 {@link MarkdownDocumentChunker#chunk} 完成最终切分</li>
     * </ol>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code chunkerCache.computeIfAbsent(key, ...)}</b>：
     *       ConcurrentHashMap 的原子操作，如果 key 不存在则执行 lambda 创建并放入，
     *       如果已存在则直接返回。保证线程安全且避免重复创建。</li>
     *   <li><b>{@code config.isEnableSemantic() ? embeddingModel : null}</b>：
     *       语义开关控制。只有法律文档等需要语义切分的场景才传入 EmbeddingModel，
     *       其他场景传 null 以节省向量计算开销。</li>
     * </ul>
     *
     * @param customDocument 统一文档对象（包含格式、内容、元数据）
     * @return 切片结果列表
     */
    public List<MarkdownDocumentChunker.Chunk> route(CustomDocument customDocument) {
        // ===== 步骤1：格式特殊通道 =====
        // Excel/CSV 是结构化表格数据，不适合 Markdown 递归切分，直接走按行切分
        if (customDocument.getFormat() == CustomDocument.Format.EXCEL ||
                customDocument.getFormat() == CustomDocument.Format.CSV) {
            log.info("文档 [{}] 为表格格式，路由到 Excel 行切分器",
                    customDocument.getFileName());
            return chunkExcelByRows(customDocument);
        }

        // ===== 步骤2：提取文本内容 =====
        // PDF/DOCX/MD/TXT/OCR 后的图片都统一为纯文本
        String content = customDocument.getContent();
        if (content == null || content.isBlank()) {
            log.warn("文档 [{}] 内容为空，跳过", customDocument.getFileName());
            return List.of();
        }

        // ===== 步骤3：内容特征分析 =====
        // ContentAnalyzer 对前 3000 字符采样，计算技术/法律/表格三维得分，输出分类
        ContentCategory category = contentAnalyzer.analyze(content);
        log.info("文档 [{}] 分类为: {}", customDocument.getFileName(), category);

        // ===== 步骤4：根据分类获取配置 =====
        // 四种分类对应四种预设配置（见 ChunkingConfig 类）
        ChunkingConfig config = getConfigForCategory(category);

        // ===== 步骤5：获取或创建对应的 Chunker（带缓存） =====
        // 以配置参数为 Key，避免为每个文档重复创建 Chunker 实例
        String cacheKey = buildCacheKey(config);
        log.info("使用配置缓存 Key: {}", cacheKey);
        MarkdownDocumentChunker chunker = chunkerCache.computeIfAbsent(cacheKey, key -> {
            log.debug("创建新的 Chunker 实例: {}", cacheKey);
            // 根据配置决定是否启用语义切分
            // enableSemantic=false → 传 null 避免 Embedding 调用
            EmbeddingModel modelToUse = config.isEnableSemantic()
                    ? embeddingModel : null;
            return new MarkdownDocumentChunker(
                    config.getMinTokens(),
                    config.getMaxTokens(),
                    config.getOverlapRatio(),
                    tokenEstimator,
                    modelToUse,
                    config.getSemanticThreshold()
            );
        });

        // ===== 步骤6：执行切分 =====
        List<MarkdownDocumentChunker.Chunk> chunks = chunker.chunk(content);
        log.info("文档 [{}] 切分完成: {} 个 chunk",
                customDocument.getFileName(), chunks.size());
        return chunks;
    }

    // ======================== 配置映射 ========================

    /**
     * <h3>根据内容分类获取切分配置</h3>
     *
     * <p>映射关系：</p>
     * <table>
     *   <tr><th>分类</th><th>minTokens</th><th>maxTokens</th><th>overlap</th><th>语义</th><th>说明</th></tr>
     *   <tr><td>TECHNICAL</td><td>200</td><td>1000</td><td>10%</td><td>关</td><td>依赖代码块/标题结构</td></tr>
     *   <tr><td>LEGAL</td><td>300</td><td>1200</td><td>25%</td><td>开</td><td>法条无标题，需语义聚类 + 大重叠防切断</td></tr>
     *   <tr><td>TABLE_HEAVY</td><td>100</td><td>800</td><td>5%</td><td>关</td><td>表格按行切，小重叠</td></tr>
     *   <tr><td>GENERAL</td><td>100</td><td>512</td><td>10%</td><td>关</td><td>递归切分足够</td></tr>
     * </table>
     *
     * @param category 内容分类
     * @return 对应的切分配置
     */
    private ChunkingConfig getConfigForCategory(ContentCategory category) {
        switch (category) {
            case TECHNICAL:
                return ChunkingConfig.TECHNICAL;
            case LEGAL:
                return ChunkingConfig.LEGAL;
            case TABLE_HEAVY:
                return ChunkingConfig.TABLE_HEAVY;
            case GENERAL:
            default:
                return ChunkingConfig.GENERAL;
        }
    }

    /**
     * <h3>构建缓存 Key</h3>
     *
     * <p>用配置的核心参数拼接为字符串，作为 Chunker 缓存 Map 的 Key。</p>
     *
     * <h4>Key 格式</h4>
     * <pre>{minTokens}_{maxTokens}_{overlapRatio}_{enableSemantic}_{semanticThreshold}</pre>
     * <p>示例：{@code "200_1000_0.10_false_0.70"} 对应 TECHNICAL 配置。</p>
     *
     * <p>只有配置参数完全相同的文档才会复用同一个 Chunker 实例，
     * 避免参数不同的文档使用错误的切分策略。</p>
     *
     * @param config 切分配置
     * @return 缓存 Key 字符串
     */
    private String buildCacheKey(ChunkingConfig config) {
        return String.format("%d_%d_%.2f_%b_%.2f",
                config.getMinTokens(),
                config.getMaxTokens(),
                config.getOverlapRatio(),
                config.isEnableSemantic(),
                config.getSemanticThreshold());
    }

    // ======================== Excel/CSV 特殊切分 ========================

    /**
     * <h3>Excel/CSV 按行切分</h3>
     *
     * <p>将表格数据按行切分为独立的 Chunk，每行数据 + 表头拼接为一个完整片段。</p>
     *
     * <h4>切分逻辑</h4>
     * <ol>
     *   <li>按换行符分割文本为行数组</li>
     *   <li>第一行作为<b>表头</b>（header），后续每行作为<b>数据行</b></li>
     *   <li>每个 Chunk = 表头 + "\n" + 数据行</li>
     *   <li>空行自动跳过</li>
     *   <li>行数过少（&lt;2）则降级到默认切分器</li>
     * </ol>
     *
     * <h4>设计理由</h4>
     * <p>表格数据具有高度结构化的特点，按 Markdown 递归切分会破坏"表头+数据行"的对应关系，
     * 导致检索时返回的行数据失去上下文。按行切分保证每行数据都携带完整的表头信息。</p>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code content.split("\n")}</b>：假设传入内容已解析为 CSV 文本或 Markdown 表格格式</li>
     *   <li><b>{@code tokenEstimator.estimateTokenCountInText(chunkText)}</b>：
     *       估算每个 Chunk 的 token 数，用于后续的大小控制和排序</li>
     *   <li><b>{@code "ExcelRow"}</b>：Chunk 的来源标签，标识这是按行切分的表格数据，
     *       检索时可用于区分来源类型</li>
     * </ul>
     *
     * @param customDocument Excel/CSV 文档对象
     * @return 按行切分的 Chunk 列表
     */
    private List<MarkdownDocumentChunker.Chunk> chunkExcelByRows(
            CustomDocument customDocument) {
        String content = customDocument.getContent();
        if (content == null || content.isBlank()) return List.of();

        // 按换行符分割为行数组
        String[] lines = content.split("\n");
        if (lines.length < 2) {
            // 不足两行（可能只有表头），降级到默认切分器
            log.warn("Excel 内容行数过少，降级为默认切分");
            return routeToDefaultChunker(customDocument);
        }

        // 提取表头（第一行）
        String header = lines[0].strip();
        List<MarkdownDocumentChunker.Chunk> chunks = new ArrayList<>();

        // 从第二行开始，每行数据 + 表头拼接为一个 Chunk
        for (int i = 1; i < lines.length; i++) {
            String row = lines[i].strip();
            if (row.isBlank()) continue;  // 跳过空行

            // 拼接：表头 + 换行 + 当前数据行
            // 这样检索时每行数据都有完整的字段名上下文
            String chunkText = header + "\n" + row;
            int tokenCount = tokenEstimator.estimateTokenCountInText(chunkText);
            chunks.add(new MarkdownDocumentChunker.Chunk(
                    chunkText, tokenCount, "ExcelRow"));
        }

        log.info("Excel 按行切分完成: {} 行", chunks.size());
        return chunks;
    }

    /**
     * <h3>降级方案：走默认 GENERAL 配置</h3>
     *
     * <p>当特殊通道失败时（如 Excel 行数过少、内容格式异常），
     * 降级到 GENERAL 通用配置进行切分。降级时关闭语义切分以节省资源。</p>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code null}</b>：传入 null 作为 EmbeddingModel，
     *       强制关闭语义切分。降级场景不需要语义分析，避免额外开销。</li>
     *   <li><b>{@code chunkerCache.computeIfAbsent}</b>：降级 Chunker 同样走缓存，
     *       不会为每个降级文档重复创建实例。</li>
     * </ul>
     *
     * @param customDocument 文档对象
     * @return 按通用配置切分的 Chunk 列表
     */
    private List<MarkdownDocumentChunker.Chunk> routeToDefaultChunker(
            CustomDocument customDocument) {
        ChunkingConfig config = ChunkingConfig.GENERAL;
        String cacheKey = buildCacheKey(config);
        MarkdownDocumentChunker chunker = chunkerCache.computeIfAbsent(
                cacheKey, key ->
                        new MarkdownDocumentChunker(
                                config.getMinTokens(),
                                config.getMaxTokens(),
                                config.getOverlapRatio(),
                                tokenEstimator,
                                null,  // 降级场景关闭语义切分
                                config.getSemanticThreshold()
                        )
        );
        return chunker.chunk(customDocument.getContent());
    }
}