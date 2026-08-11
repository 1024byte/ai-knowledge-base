package com.hai.aiknowledgebase.config;

import lombok.Builder;
import lombok.Data;

/**
 * <h2>文档切分配置</h2>
 *
 * <p>针对不同内容类型（技术文档、法律文本、表格、通用文档、试卷）提供差异化切分参数。
 * 各参数的设计依据详见各预设配置的注释。</p>
 *
 * <h3>参数说明</h3>
 * <table>
 *   <tr><th>参数</th><th>含义</th><th>影响因素</th></tr>
 *   <tr><td>minTokens</td><td>单个 Chunk 最小 token 数。低于此值的片段会被 mergeSmallChunks 合并</td><td>内容类型的最小语义单元大小</td></tr>
 *   <tr><td>maxTokens</td><td>单个 Chunk 最大 token 数。超过此值触发切分（splitLargeSection / semanticChunk）</td><td>嵌入模型上下文窗口、内容语义完整性</td></tr>
 *   <tr><td>overlapRatio</td><td>相邻 Chunk 重叠比例。前一个 Chunk 尾部按此比例复制到下一个 Chunk 头部</td><td>内容边界敏感性、检索召回完整性</td></tr>
 *   <tr><td>enableSemantic</td><td>是否启用基于句子向量余弦相似度的语义切分</td><td>内容是否有天然标题结构</td></tr>
 *   <tr><td>semanticThreshold</td><td>语义转折阈值（0.0~1.0）。句子与累积向量相似度低于此值时触发切分</td><td>语义转折的敏感度需求</td></tr>
 * </table>
 *
 * @see com.hai.aiknowledgebase.service.MarkdownDocumentChunker 文档切片器
 * @see com.hai.aiknowledgebase.service.DocumentRouter 文档路由器
 */
@Data
@Builder
public class ChunkingConfig {
    private int minTokens;
    private int maxTokens;
    private double overlapRatio;
    private boolean enableSemantic;
    private double semanticThreshold;

    /** 父上下文窗口大小：每个 chunk 的 parent_text 包含自身 + 前后各 PARENT_WINDOW 个 chunk */
    public static final int PARENT_WINDOW = 2;

    // ======================== 预设配置 ========================

    /**
     * <h3>技术文档配置 (TECHNICAL)</h3>
     *
     * <h4>minTokens = 200</h4>
     * <p>技术文档的代码块、API 说明、配置示例等通常有完整语义单元。
     * 200 token ≈ 150 英文单词 ≈ 300 中文字符，足以覆盖一个完整的函数签名 + 简短说明，
     * 或一个 Markdown 小标题 + 一段正文。低于此值的片段（如单行注释、短代码片段）
     * 缺乏独立检索价值，应被 mergeSmallChunks 合并到相邻 Chunk。</p>
     *
     * <h4>maxTokens = 1000</h4>
     * <p>技术文档的段落通常较长（代码块、配置示例、API 文档等），1000 token ≈ 750 英文单词
     * 能容纳一个完整的代码块 + 说明文字。虽然嵌入模型上下文窗口通常为 512 token，
     * 但检索时取 parent_text（自身 + 前后各 PARENT_WINDOW=2 个 chunk）会扩展上下文，
     * 最终送入 LLM 的上下文由 buildContextWithinBudget 按 token 预算截断，因此 1000 是安全的。
     * 参考：OpenAI text-embedding-3-small 最大输入 8191 token，1000 远低于上限。</p>
     *
     * <h4>overlapRatio = 0.1 (10%)</h4>
     * <p>技术文档靠 Markdown 标题/代码块结构切分，边界清晰（如标题切换、代码块结束），
     * 10% 重叠足以防止边界处的关键信息丢失。参考：LangChain RecursiveCharacterTextSplitter
     * 默认 chunk_overlap 为 chunk_size 的 10%。</p>
     *
     * <h4>enableSemantic = false</h4>
     * <p>技术文档有明确的 Markdown 结构（代码块包裹、标题层级），递归结构切分足以保证语义完整。
     * 语义切分反而可能破坏代码块完整性（代码块内句子相似度可能很低，导致错误切分）。</p>
     *
     * <h4>semanticThreshold = 0.7</h4>
     * <p>语义切分未启用，此值为占位默认值。0.7 是余弦相似度的常见经验阈值
     * （约 45° 夹角），处于"相似"与"不相似"的分界区域。</p>
     */
    public static final ChunkingConfig TECHNICAL = ChunkingConfig.builder()
            .minTokens(200)
            .maxTokens(1000)
            .overlapRatio(0.1)
            .enableSemantic(false)
            .semanticThreshold(0.7)
            .build();

    /**
     * <h3>法律文档配置 (LEGAL)</h3>
     *
     * <h4>minTokens = 300</h4>
     * <p>法律条款通常以"条"为最小语义单元，每条法条含完整的主文 + 款/项，语义自包含。
     * 300 token ≈ 225 英文单词 ≈ 450 中文字符，能覆盖大多数法条 + 简短解释。
     * 过低会导致法条被拆散，检索时无法召回完整条款。</p>
     *
     * <h4>maxTokens = 1200</h4>
     * <p>法律条文经常包含复杂的嵌套条款和多层引用（如"依照前条第二款..."），
     * 1200 token ≈ 900 英文单词能容纳完整条款避免截断。
     * 法律文本信息密度高，每条条款都有独立检索价值，需要比技术文档更大的 maxTokens。
     * 参考：LlamaIndex 建议法律文档 chunk_size 为 1024-2048 token。</p>
     *
     * <h4>overlapRatio = 0.25 (25%)</h4>
     * <p>法律文本的最大特点：法条之间高度关联（"依据前条..."、"参照第X条..."、"前款规定的..."），
     * 大重叠（25%）防止切断法条引用链，确保检索时能召回关联条款。
     * 这是所有预设中最高的重叠比例，因为法律文本的边界最不清晰。
     * 参考：RAG 最佳实践中，法律/合同类文档建议 overlap 20%-30%。</p>
     *
     * <h4>enableSemantic = true</h4>
     * <p>法律文本通常无 Markdown 标题结构（或仅有"第X章"级别的大标题），
     * 无法依赖结构切分。语义切分基于句子向量余弦相似度，能在条款边界处自然切分，
     * 是唯一适合法律文本的切分策略。</p>
     *
     * <h4>semanticThreshold = 0.75</h4>
     * <p>法律文本的语义转折较温和（条款间仍有关联，不像技术文档中代码块和说明文字那样差异大），
     * 略高的阈值（0.75 vs 默认 0.7）能更敏感地检测到条款边界，避免相邻条款被错误合并。
     * 参考：余弦相似度 0.75 ≈ 41° 夹角，处于"中等相似"与"高度相似"的分界，
     * 在语义切分实践中，0.7-0.8 是常见的推荐阈值范围。</p>
     */
    public static final ChunkingConfig LEGAL = ChunkingConfig.builder()
            .minTokens(300)
            .maxTokens(1200)
            .overlapRatio(0.25)
            .enableSemantic(true)
            .semanticThreshold(0.75)
            .build();

    /**
     * <h3>表格密集文档配置 (TABLE_HEAVY)</h3>
     *
     * <h4>minTokens = 100</h4>
     * <p>表格的单个单元格或行内容简短，100 token ≈ 75 英文单词能容纳 3-5 行表格数据。
     * 低于此值的片段（如标题行、空行）无独立检索价值。</p>
     *
     * <h4>maxTokens = 800</h4>
     * <p>表格数据本身信息密度高（纯数据无冗余），800 token 能容纳一个完整表格
     * （含表头 + 10-20 行数据）。Markdown 表格按 TABLE_PATTERN 正则匹配三行结构
     * （表头 + 分隔行 + 数据行），maxTokens 过大可能将多个表格合并为一个 Chunk，
     * 降低检索精度。参考：表格数据建议较小 chunk（512-1024 token），
     * 因为每行数据都是独立信息单元。</p>
     *
     * <h4>overlapRatio = 0.05 (5%)</h4>
     * <p>表格按行切分，边界在表格行之间，非常清晰。少量重叠（5%）足够覆盖边界处
     * 的表头信息（表头会被复制到重叠区域）。如果重叠比例过高，会导致表格数据重复，
     * 降低检索精度。</p>
     *
     * <h4>enableSemantic = false</h4>
     * <p>表格数据是结构化数据，语义切分无意义。表格的边界由 Markdown 管道符语法
     * 和 TABLE_PATTERN 正则匹配确定，与语义无关。</p>
     *
     * <h4>semanticThreshold = 0.7</h4>
     * <p>语义切分未启用，占位值。</p>
     */
    public static final ChunkingConfig TABLE_HEAVY = ChunkingConfig.builder()
            .minTokens(100)
            .maxTokens(800)
            .overlapRatio(0.05)
            .enableSemantic(false)
            .semanticThreshold(0.7)
            .build();

    /**
     * <h3>通用文档配置 (GENERAL)</h3>
     *
     * <h4>minTokens = 100</h4>
     * <p>通用文档内容多样，100 token ≈ 75 英文单词 ≈ 150 中文字符是最小有效语义单元，
     * 大致相当于一个中等长度的段落。</p>
     *
     * <h4>maxTokens = 512</h4>
     * <p><b>核心出处：</b>这是 OpenAI text-embedding-ada-002 和许多早期嵌入模型
     * 的建议 chunk 大小。虽然新模型（text-embedding-3-small/large）支持更大输入，
     * 但 512 token 已被大量 RAG 实践验证为"检索精度与语义完整性"的最佳平衡点。
     * 参考：OpenAI Cookbook 建议 chunk_size=512、LangChain 默认 chunk_size=1000 字符 ≈ 250 token，
     * LlamaIndex 默认 chunk_size=1024 token。此处取 512 是折中方案，
     * 兼顾检索精度（小 chunk）和语义完整（大 chunk）。</p>
     *
     * <h4>overlapRatio = 0.1 (10%)</h4>
     * <p>10% 是通用文档切分的业界默认值。参考：LangChain RecursiveCharacterTextSplitter
     * 默认 chunk_overlap=200 字符 / chunk_size=1000 字符 = 20%，但 10% 对于有标题结构的
     * 通用文档更合适，因为标题边界天然提供了切分点，重叠需求更低。</p>
     *
     * <h4>enableSemantic = false</h4>
     * <p>通用文档的递归切分（基于标题层级 + token 约束）已足够覆盖大多数场景。
     * 语义切分需要额外的 Embedding 调用，成本高且对通用文档提升有限。</p>
     *
     * <h4>semanticThreshold = 0.7</h4>
     * <p>语义切分未启用，占位值。</p>
     */
    public static final ChunkingConfig GENERAL = ChunkingConfig.builder()
            .minTokens(100)
            .maxTokens(512)
            .overlapRatio(0.1)
            .enableSemantic(false)
            .semanticThreshold(0.7)
            .build();

    /**
     * <h3>试卷/试题文档配置 (EXAM_PAPER)</h3>
     *
     * <h4>minTokens = 200</h4>
     * <p>题目通常包含题干 + 选项 + 简短解析，200 token 能覆盖一道完整的选择题或填空题。
     * 低于此值的片段（如单个选项、词汇表条目）缺乏独立检索价值。</p>
     *
     * <h4>maxTokens = 800</h4>
     * <p>英文阅读理解题可能有长段落（200-400 单词）+ 多道小题（5-10 道），
     * 800 token 能容纳完整题目组。中文试卷通常 300-500 token 足够。
     * 800 是折中值：既能容纳英文长段落，又不会因过大而降低检索精度。
     * 参考：试卷文档建议 chunk_size 在 512-1024 之间，
     * 因为每道题是独立检索单元，过大会导致检索结果包含不相关题目。</p>
     *
     * <h4>overlapRatio = 0.15 (15%)</h4>
     * <p>试题间有关联：阅读理解中多道题共享同一篇文章，完形填空中上下文连贯。
     * 15% 重叠略高于通用文档的 10%，保证上下文衔接，但低于法律文档的 25%，
     * 因为试题的边界仍然清晰（题目编号、选项标记）。</p>
     *
     * <h4>enableSemantic = false</h4>
     * <p>试题依赖结构（题目编号如 "1."、"2."、选项标记如 "A."、"B."），
     * 语义切分可能破坏题目结构（如将题干和选项切分到不同 Chunk）。</p>
     *
     * <h4>semanticThreshold = 0.7</h4>
     * <p>语义切分未启用，占位值。</p>
     */
    public static final ChunkingConfig EXAM_PAPER = ChunkingConfig.builder()
            .minTokens(200)
            .maxTokens(800)
            .overlapRatio(0.15)
            .enableSemantic(false)
            .semanticThreshold(0.7)
            .build();
}