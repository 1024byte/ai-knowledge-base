package com.hai.aiknowledgebase.service;

import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

import java.text.BreakIterator;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>Markdown 文档智能切片器</h2>
 *
 * <p>将 Markdown 文档按以下策略切分为语义完整的 Chunk：</p>
 * <ol>
 *   <li><b>层级感知</b>：解析 Markdown 标题（H1-H6），构建 Section 树，保留文档结构</li>
 *   <li><b>内容块识别</b>：区分代码块、表格、普通段落，代码块和表格不可拆分</li>
 *   <li><b>语义切分</b>：基于句子向量余弦相似度，在语义转折处自然切分</li>
 *   <li><b>Token 约束</b>：每个 Chunk 的 token 数控制在 [minTokens, maxTokens] 区间</li>
 *   <li><b>重叠策略</b>：相邻 Chunk 之间按 overlapRatio 添加尾部重叠，防止边界信息丢失</li>
 *   <li><b>上下文前缀</b>：每个 Chunk 携带层级路径（如 "第一章 > 1.1 概述"），增强检索可解释性</li>
 * </ol>
 *
 * <h3>降级策略</h3>
 * 嵌入模型不可用或语义切分异常时，自动降级为纯 token 计数切分，保证服务不中断。
 */
@Slf4j
public class MarkdownDocumentChunker {

    // ======================== 预编译正则表达式 ========================

    /** 匹配 Markdown 标题行：{@code #} 到 {@code ######} 开头 */
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /** 匹配 Markdown 表格：管道符 {@code |} 格式的三行结构（表头 + 分隔行 + 数据行） */
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(^\\|.+)\\n(\\|?[-:| ]+)\\n((?:\\|.+\\n?)+)",
            Pattern.MULTILINE
    );

    /** 匹配 Markdown 代码块：{@code ```} 包裹的任意内容 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```[a-zA-Z0-9_]*\\s*[\\s\\S]*?```",
            Pattern.MULTILINE
    );

    // ======================== 配置参数 ========================

    /** 每个 Chunk 的最小 token 数，低于此值的 Chunk 会尝试与相邻 Chunk 合并 */
    private final int minTokens;

    /** 每个 Chunk 的最大 token 数，超过此值会触发切分 */
    private final int maxTokens;

    /** 相邻 Chunk 之间的重叠比例（0.0 ~ 1.0），默认 0.2 即 20% */
    private final double overlapRatio;

    /** Token 计数估算器，用于估算文本的 token 数量 */
    private final TokenCountEstimator tokenEstimator;

    /** 嵌入模型，用于生成句子向量以支持语义切分（可为 null，此时降级为纯 token 切分） */
    private final EmbeddingModel embeddingModel;

    /** 语义切分的相似度阈值：句子与累积向量的余弦相似度低于此值时触发切分 */
    private final double semanticThreshold;

    /**
     * 构造 Markdown 文档切片器。
     *
     * @param minTokens         每个 Chunk 的最小 token 数
     * @param maxTokens         每个 Chunk 的最大 token 数
     * @param overlapRatio      相邻 Chunk 重叠比例（0.0 ~ 1.0）
     * @param tokenEstimator    Token 计数估算器
     * @param embeddingModel    嵌入模型（可为 null，降级为纯 token 切分）
     * @param semanticThreshold 语义切分相似度阈值
     */
    public MarkdownDocumentChunker(int minTokens, int maxTokens, double overlapRatio,
                                   TokenCountEstimator tokenEstimator,
                                   EmbeddingModel embeddingModel,
                                   double semanticThreshold) {
        this.minTokens = minTokens;
        this.maxTokens = maxTokens;
        this.overlapRatio = overlapRatio;
        this.tokenEstimator = tokenEstimator;
        this.embeddingModel = embeddingModel;
        this.semanticThreshold = semanticThreshold;
    }

    // ======================== 主入口 ========================

    /**
     * <h3>对 Markdown 文档执行智能切片</h3>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li><b>解析层级</b>：{@link #parseSections} 解析 Markdown 标题，构建 Section 树</li>
     *   <li><b>迭代切片</b>：{@link #iterativeChunk} 栈式遍历 Section 树，生成原始 Chunk</li>
     *   <li><b>合并小片段</b>：{@link #mergeSmallChunks} 将低于 minTokens 的片段合并</li>
     *   <li><b>添加重叠</b>：{@link #applyOverlap} 在相邻 Chunk 之间添加尾部重叠</li>
     * </ol>
     *
     * @param markdown 原始 Markdown 文本
     * @return 切分后的 Chunk 列表（不可变）
     */
    public List<Chunk> chunk(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        // 步骤1：解析 Markdown 标题层级，构建 Section 树
        List<Section> sections = parseSections(markdown);
        // 步骤2：栈式迭代遍历 Section 树，生成原始 Chunk 列表
        List<Chunk> rawChunks = iterativeChunk(sections, "");
        // 步骤3+4：合并小片段 + 添加重叠（在 applyOverlap 内部完成）
        List<Chunk> overlappedChunks = applyOverlap(rawChunks);
        log.debug("切片完成: {} 个 section → {} 个原始 chunk → {} 个重叠 chunk",
                sections.size(), rawChunks.size(), overlappedChunks.size());
        return overlappedChunks;
    }

    // ======================== Section 数据结构 ========================

    /**
     * <h3>文档章节（Section）</h3>
     *
     * <p>表示 Markdown 中的一个标题及其内容，支持树形嵌套。
     * 例如 H1 下的 H2 是 H1 的 children。</p>
     *
     * <h4>字段说明</h4>
     * <ul>
     *   <li><b>level</b>：标题层级，0 表示无标题的前导文本</li>
     *   <li><b>title</b>：标题文本（如 "## 概述"）</li>
     *   <li><b>content</b>：标题之后的正文内容（不含子标题）</li>
     *   <li><b>children</b>：子 Section 列表</li>
     * </ul>
     */
    static class Section {
        /** 标题层级：0=无标题, 1=H1, 2=H2, ..., 6=H6 */
        int level;
        /** 标题文本（含 # 前缀） */
        String title;
        /** 标题之后的正文内容（不含子标题及其内容） */
        String content;
        /** 子 Section 列表 */
        List<Section> children = new ArrayList<>();

        Section(int level, String title, String content) {
            this.level = level;
            this.title = title;
            this.content = content;
        }

        /**
         * 获取当前 Section 及其所有子 Section 的完整文本。
         * 格式：标题 + 内容 + 递归拼接所有子节点的 fullText。
         */
        String fullText() {
            StringBuilder sb = new StringBuilder();
            if (!title.isEmpty()) sb.append(title).append("\n");
            if (!content.isEmpty()) sb.append(content).append("\n");
            for (Section child : children) {
                sb.append(child.fullText()).append("\n");
            }
            return sb.toString().stripTrailing();
        }

        /**
         * 计算当前 Section 自身（不含子节点）的 token 数。
         * 仅统计 title + content 的 token 数。
         */
        int ownTokenCount(TokenCountEstimator estimator) {
            int tokens = 0;
            if (!title.isEmpty()) tokens += estimator.estimateTokenCountInText(title);
            if (!content.isEmpty()) tokens += estimator.estimateTokenCountInText(content);
            return tokens;
        }

        /**
         * 计算当前 Section 及其所有子节点的总 token 数。
         * 递归累加自身 + 所有 children 的 token 数。
         */
        int totalTokenCount(TokenCountEstimator estimator) {
            int tokens = ownTokenCount(estimator);
            for (Section child : children) {
                tokens += child.totalTokenCount(estimator);
            }
            return tokens;
        }
    }

    // ======================== Section 解析 ========================

    /**
     * <h3>解析 Markdown 文本为 Section 树</h3>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>调用 {@link #findHeadings} 找到所有标题行及其位置</li>
     *   <li>如果第一个标题之前有文本，包装为 level=0 的前导 Section</li>
     *   <li>将每个标题行 + 后续内容（直到下一个标题）构成一个扁平 Section</li>
     *   <li>调用 {@link #buildSectionTree} 将扁平列表转为树结构</li>
     * </ol>
     *
     * @param markdown 原始 Markdown 文本
     * @return Section 树的根节点列表
     */
    List<Section> parseSections(String markdown) {
        List<Section> sections = new ArrayList<>();
        List<HeadingInfo> headings = findHeadings(markdown);

        // 情况1：没有任何标题 → 整个文档作为一个 level=0 的 Section
        if (headings.isEmpty()) {
            sections.add(new Section(0, "", markdown.strip()));
            return sections;
        }

        // 情况2：第一个标题之前有前导文本 → 包装为 level=0 的 Section
        int firstHeadingStart = headings.get(0).start;
        if (firstHeadingStart > 0) {
            String preamble = markdown.substring(0, firstHeadingStart).strip();
            if (!preamble.isEmpty()) {
                sections.add(new Section(0, "", preamble));
            }
        }

        // 情况3：遍历每个标题，将其与后续内容（到下一个标题为止）构成 Section
        for (int i = 0; i < headings.size(); i++) {
            HeadingInfo heading = headings.get(i);
            int contentStart = heading.end;
            // 内容结束位置 = 下一个标题的起始位置（或文档末尾）
            int contentEnd = (i + 1 < headings.size())
                    ? headings.get(i + 1).start : markdown.length();
            String content = markdown.substring(contentStart, contentEnd).strip();
            sections.add(new Section(heading.level, heading.fullLine, content));
        }

        // 将扁平 Section 列表转为树结构
        return buildSectionTree(sections);
    }

    /**
     * <h3>标题信息记录</h3>
     *
     * @param level    标题层级（1=H1, 2=H2, ...）
     * @param fullLine 完整标题行（如 "## 概述"）
     * @param start    标题在原文中的起始位置
     * @param end      标题在原文中的结束位置（下一行的起始）
     */
    record HeadingInfo(int level, String fullLine, int start, int end) {}

    /**
     * <h3>查找 Markdown 中所有标题行</h3>
     *
     * <p>使用正则 {@code ^(#{1,6})\s+(.+)$} 匹配所有 H1-H6 标题。</p>
     *
     * @param markdown 原始 Markdown 文本
     * @return 按出现顺序排列的标题信息列表
     */
    private List<HeadingInfo> findHeadings(String markdown) {
        List<HeadingInfo> headings = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(markdown);
        while (matcher.find()) {
            int level = matcher.group(1).length();   // # 的数量 = 层级
            String fullLine = matcher.group(0);       // 完整标题行
            int start = matcher.start();              // 起始位置
            int end = matcher.end();                  // 结束位置
            headings.add(new HeadingInfo(level, fullLine, start, end));
        }
        return headings;
    }

    /**
     * <h3>将扁平 Section 列表构建为树结构</h3>
     *
     * <h4>算法：栈式层级管理</h4>
     * <pre>
     * 示例标题序列: [H1, H2, H3, H2]
     *
     * 栈状态变化:
     *   H1 入栈 → roots=[H1], stack=[H1]
     *   H2 入栈 → H1.children=[H2], stack=[H1, H2]
     *   H3 入栈 → H2.children=[H3], stack=[H1, H2, H3]
     *   H2 入栈 → pop H3, pop H2, stack=[H1] → H1.children=[H2(新)]
     * </pre>
     *
     * <p>核心规则：每次遇到新 Section，弹出栈中所有 level >= 当前 level 的 Section，
     * 然后将当前 Section 添加为栈顶 Section 的子节点。</p>
     *
     * @param flatSections 扁平 Section 列表（已按出现顺序排列）
     * @return Section 树的根节点列表
     */
    private List<Section> buildSectionTree(List<Section> flatSections) {
        List<Section> roots = new ArrayList<>();
        // 栈：维护当前路径上的所有 Section（从根到当前叶子）
        List<Section> stack = new ArrayList<>();

        for (Section section : flatSections) {
            // 弹出所有层级 >= 当前 Section 的节点
            // 例如：当前是 H2，栈中有 [H1, H2, H3]，弹出 H3 和 H2
            while (!stack.isEmpty()
                    && stack.get(stack.size() - 1).level >= section.level) {
                stack.remove(stack.size() - 1);
            }

            if (stack.isEmpty()) {
                // 栈为空 → 当前 Section 是新的根节点
                roots.add(section);
            } else {
                // 栈非空 → 当前 Section 是栈顶 Section 的子节点
                stack.get(stack.size() - 1).children.add(section);
            }
            // 当前 Section 入栈，成为路径上的最新节点
            stack.add(section);
        }
        return roots;
    }

    // ======================== 迭代切片（栈式模拟递归） ========================

    /**
     * <h3>栈式迭代遍历 Section 树，生成 Chunk 列表</h3>
     *
     * <h4>为什么用显式栈而不是递归？</h4>
     * <ul>
     *   <li>避免深层嵌套 Markdown 导致 {@link StackOverflowError}</li>
     *   <li>更精细地控制帧上下文（通过 {@code FrameMarker} 标记帧边界）</li>
     * </ul>
     *
     * <h4>栈元素类型</h4>
     * <ul>
     *   <li><b>Section</b>：待处理的章节，需要遍历其 content 和 children</li>
     *   <li><b>FrameMarker</b>：标记一个处理帧的结束，
     *       弹出时执行 mergeSmallChunks 并将结果追加到父帧</li>
     * </ul>
     *
     * <h4>处理策略</h4>
     * <ul>
     *   <li>Section 总 token ≤ maxTokens → 直接打包为 1 个 Chunk</li>
     *   <li>Section 总 token > maxTokens 且 有子节点 →
     *       先拆分自身内容，再递归处理子节点</li>
     *   <li>Section 总 token > maxTokens 且 无子节点 →
     *       调用 {@link #splitLargeSection} 拆分</li>
     * </ul>
     *
     * @param sections      Section 树的根节点列表
     * @param contextPrefix 当前层级路径前缀（如 "第一章 > 1.1 概述"）
     * @return 当前层级下的所有 Chunk
     */
    private List<Chunk> iterativeChunk(List<Section> sections, String contextPrefix) {
        // FrameMarker：标记一个帧的边界，用于在帧结束时执行合并操作
        record FrameMarker(String contextPrefix) {}

        // 主栈：交替存储 Section 和 FrameMarker
        Deque<Object> stack = new ArrayDeque<>();
        // 先压入帧标记，再逆序压入所有 Section（保证正序处理）
        stack.push(new FrameMarker(contextPrefix));
        for (int i = sections.size() - 1; i >= 0; i--) {
            stack.push(sections.get(i));
        }

        // 帧栈：每个帧对应一个 Section 层级，存储该层级产生的 Chunk
        Deque<List<Chunk>> chunkFrameStack = new ArrayDeque<>();
        // 前缀栈：与帧栈同步，存储每个帧的上下文前缀
        Deque<String> prefixFrameStack = new ArrayDeque<>();
        chunkFrameStack.push(new ArrayList<>());
        prefixFrameStack.push(contextPrefix);

        while (!stack.isEmpty()) {
            Object item = stack.pop();

            if (item instanceof FrameMarker marker) {
                // ===== 帧结束：合并当前帧的所有 Chunk，追加到父帧 =====
                if (!prefixFrameStack.peek().equals(marker.contextPrefix)) {
                    log.warn("帧上下文不匹配，预期: {}, 实际: {}",
                            marker.contextPrefix, prefixFrameStack.peek());
                }
                List<Chunk> currentFrameChunks = chunkFrameStack.pop();
                prefixFrameStack.pop();
                // 合并当前帧中的小 Chunk（低于 minTokens 的片段）
                List<Chunk> merged = mergeSmallChunks(currentFrameChunks);
                if (!chunkFrameStack.isEmpty()) {
                    // 将合并后的结果追加到父帧
                    chunkFrameStack.peek().addAll(merged);
                } else {
                    chunkFrameStack.push(merged);
                }
                continue;
            }

            Section section = (Section) item;
            int totalTokens = section.totalTokenCount(tokenEstimator);
            String currentPrefix = prefixFrameStack.peek();
            List<Chunk> currentFrameChunks = chunkFrameStack.peek();

            if (totalTokens <= maxTokens) {
                // ===== 策略1：Section 不超限 → 直接打包 =====
                String sectionPrefix = buildContextPrefix(currentPrefix, section);
                String text = section.fullText();
                if (!text.isBlank()) {
                    currentFrameChunks.add(new Chunk(
                            wrapWithContext(text, sectionPrefix),
                            tokenEstimator.estimateTokenCountInText(text),
                            sectionPrefix));
                }

            } else if (!section.children.isEmpty()) {
                // ===== 策略2：Section 超限但有子节点 → 递归处理 =====
                // 构建当前 Section 的上下文前缀（级联当前标题）
                String sectionPrefix2 = buildContextPrefix(currentPrefix, section);
                // 先处理自身内容（title + content，不含子节点）
                if (!section.title.isEmpty() || !section.content.isEmpty()) {
                    Section ownSection = new Section(
                            section.level, section.title, section.content);
                    List<Chunk> ownChunks = splitLargeSection(
                            ownSection, sectionPrefix2);
                    currentFrameChunks.addAll(ownChunks);
                }
                // 压入帧标记和子节点 Section（逆序）
                stack.push(new FrameMarker(sectionPrefix2));
                for (int i = section.children.size() - 1; i >= 0; i--) {
                    stack.push(section.children.get(i));
                }
                // 创建新的帧用于收集子节点的 Chunk
                chunkFrameStack.push(new ArrayList<>());
                prefixFrameStack.push(sectionPrefix2);

            } else {
                // ===== 策略3：Section 超限且无子节点 → 直接拆分 =====
                String sectionPrefix3 = buildContextPrefix(currentPrefix, section);
                currentFrameChunks.addAll(
                        splitLargeSection(section, sectionPrefix3));
            }
        }

        return chunkFrameStack.isEmpty() ? List.of() : chunkFrameStack.pop();
    }

    /**
     * <h3>将文本包装为带上下文前缀的格式</h3>
     *
     * <p>如果上下文前缀非空，在文本开头添加 {@code **上下文**: 前缀路径} 标记。</p>
     *
     * @param text          原始文本
     * @param contextPrefix 上下文前缀路径（如 "第一章 > 1.1 概述"）
     * @return 带上下文标记的文本
     */
    private String wrapWithContext(String text, String contextPrefix) {
        if (contextPrefix == null || contextPrefix.isEmpty()) {
            return text;
        }
        return "**上下文**: " + contextPrefix + "\n\n" + text;
    }

    // ======================== 拆分大 Section ========================

    /**
     * <h3>拆分一个超过 maxTokens 的 Section</h3>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>调用 {@link #splitIntoBlocks} 将内容分为代码块、表格、普通段落</li>
     *   <li>遍历每个块，根据块类型和大小决定处理策略：</li>
     * </ol>
     *
     * <h4>处理策略矩阵</h4>
     * <table>
     *   <tr><th>块类型</th><th>块 token 超 maxTokens</th><th>处理方式</th></tr>
     *   <tr><td>不可拆分（代码块/表格）</td><td>是</td><td>单独输出该块（即使超限）</td></tr>
     *   <tr><td>不可拆分（代码块/表格）</td><td>否</td><td>正常累积到当前 Chunk</td></tr>
     *   <tr><td>可拆分（段落）</td><td>是</td><td>调用 splitLongParagraph 语义切分</td></tr>
     *   <tr><td>可拆分（段落）</td><td>否</td><td>超过 maxTokens 则先输出当前 Chunk，再累积</td></tr>
     * </table>
     *
     * @param section       待拆分的 Section
     * @param contextPrefix 上下文前缀路径
     * @return 拆分后的 Chunk 列表
     */
    private List<Chunk> splitLargeSection(Section section, String contextPrefix) {
        // 标题头：每个 Chunk 都包含 section 的标题
        String header = section.title.isEmpty() ? "" : section.title + "\n";
        String content = section.content;

        // 步骤1：将内容拆分为可识别的内容块
        List<ContentBlock> blocks = splitIntoBlocks(content);

        List<Chunk> chunks = new ArrayList<>();
        // 当前累积的 Chunk 文本（以 header 开头）
        StringBuilder currentChunk = new StringBuilder(header);
        // 当前累积的 token 数
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);

        for (ContentBlock block : blocks) {
            int blockTokens = tokenEstimator.estimateTokenCountInText(block.text);

            if (blockTokens > maxTokens && !block.isSplittable) {
                // ===== 情况A：不可拆分的超大块（如超大代码块） → 单独输出 =====
                if (currentChunk.length() > header.length()) {
                    // 先输出当前累积的内容
                    chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                            currentTokens, contextPrefix));
                }
                int headerTokens = tokenEstimator.estimateTokenCountInText(header);
                int totalChunkTokens = headerTokens + blockTokens;
                if (totalChunkTokens > maxTokens) {
                    log.warn("不可拆分块 token 数 ({}) 超过 maxTokens ({})",
                            totalChunkTokens, maxTokens);
                }
                // 即使超限也单独输出，保证代码块完整性
                chunks.add(createChunk(header + block.text, totalChunkTokens,
                        contextPrefix));
                // 重置累积器
                currentChunk = new StringBuilder(header);
                currentTokens = headerTokens;

            } else if (blockTokens > maxTokens && block.isSplittable) {
                // ===== 情况B：可拆分的超大块（如超长段落） → 语义切分 =====
                if (currentChunk.length() > header.length()) {
                    chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                            currentTokens, contextPrefix));
                }
                // 调用语义切分或 token 降级切分
                chunks.addAll(splitLongParagraph(header, block.text, contextPrefix));
                // 重置累积器
                currentChunk = new StringBuilder(header);
                currentTokens = tokenEstimator.estimateTokenCountInText(header);

            } else if (currentTokens + blockTokens > maxTokens) {
                // ===== 情况C：加上当前块会超限 → 先输出当前 Chunk，再开始新 Chunk =====
                chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                        currentTokens, contextPrefix));
                currentChunk = new StringBuilder(header);
                currentChunk.append(block.text).append("\n");
                currentTokens = tokenEstimator.estimateTokenCountInText(header)
                        + blockTokens;

            } else {
                // ===== 情况D：正常累积 → 追加到当前 Chunk =====
                currentChunk.append(block.text).append("\n");
                currentTokens += blockTokens;
            }
        }

        // 输出最后一个未完成的 Chunk
        if (currentChunk.length() > header.length()) {
            chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                    currentTokens, contextPrefix));
        }
        return chunks;
    }

    /**
     * <h3>创建 Chunk 对象</h3>
     *
     * <p>内部调用 {@link #wrapWithContext} 包装上下文前缀。</p>
     */
    private Chunk createChunk(String rawText, int tokenCount, String contextPrefix) {
        String finalText = wrapWithContext(rawText, contextPrefix);
        return new Chunk(finalText, tokenCount, contextPrefix);
    }

    /**
     * <h3>将 Section 内容拆分为可识别的内容块</h3>
     *
     * <h4>识别三类内容块</h4>
     * <ol>
     *   <li><b>代码块</b>（{@code ```} 包裹）：isSplittable=false，不可拆分</li>
     *   <li><b>表格</b>（{@code |} 管道符格式）：isSplittable=false，不可拆分</li>
     *   <li><b>普通段落</b>（剩余文本）：isSplittable=true，可拆分</li>
     * </ol>
     *
     * <h4>关键设计：代码块优先于表格</h4>
     * <p>如果表格出现在代码块内部，通过区间重叠检测排除，避免重复识别。</p>
     *
     * @param content Section 的正文内容
     * @return 按出现顺序排列的内容块列表
     */
    private List<ContentBlock> splitIntoBlocks(String content) {
        List<ContentBlock> blocks = new ArrayList<>();
        List<BlockSpan> spans = new ArrayList<>();

        // 第一遍：识别所有代码块
        Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(content);
        while (codeMatcher.find()) {
            spans.add(new BlockSpan(codeMatcher.start(), codeMatcher.end(),
                    content.substring(codeMatcher.start(), codeMatcher.end()),
                    false));  // isSplittable=false
        }

        // 第二遍：识别所有表格（排除与代码块重叠的）
        Matcher tableMatcher = TABLE_PATTERN.matcher(content);
        while (tableMatcher.find()) {
            // 区间重叠检测：表格是否与任何已识别的代码块重叠
            boolean overlaps = spans.stream().anyMatch(s ->
                    s.start < tableMatcher.end() && s.end > tableMatcher.start());
            if (!overlaps) {
                spans.add(new BlockSpan(tableMatcher.start(), tableMatcher.end(),
                        tableMatcher.group(0),
                        false));  // isSplittable=false
            }
        }

        // 按出现位置排序
        spans.sort(Comparator.comparingInt(a -> a.start));

        // 第三遍：按位置顺序填充内容块，特殊块之间为普通段落
        int cursor = 0;
        for (BlockSpan span : spans) {
            if (span.start > cursor) {
                // 特殊块之前的文本 → 按空行分割为多个可拆分段落
                String paragraphText = content.substring(cursor, span.start).strip();
                if (!paragraphText.isEmpty()) {
                    String[] paragraphs = paragraphText.split("\\n\\s*\\n");
                    for (String para : paragraphs) {
                        String p = para.strip();
                        if (!p.isEmpty()) {
                            blocks.add(new ContentBlock(p + "\n",
                                    true));  // isSplittable=true
                        }
                    }
                }
            }
            // 添加特殊块（代码块/表格）
            blocks.add(new ContentBlock(span.text + "\n",
                    false));  // isSplittable=false
            cursor = span.end;
        }

        // 处理最后剩余的文本
        if (cursor < content.length()) {
            String remaining = content.substring(cursor).strip();
            if (!remaining.isEmpty()) {
                String[] paragraphs = remaining.split("\\n\\s*\\n");
                for (String para : paragraphs) {
                    String p = para.strip();
                    if (!p.isEmpty()) {
                        blocks.add(new ContentBlock(p + "\n", true));
                    }
                }
            }
        }

        // 没有特殊块时，整个内容按空行分割为段落
        if (spans.isEmpty()) {
            blocks.clear();
            String[] paragraphs = content.split("\\n\\s*\\n");
            for (String para : paragraphs) {
                String p = para.strip();
                if (!p.isEmpty()) {
                    blocks.add(new ContentBlock(p + "\n", true));
                }
            }
        }
        return blocks;
    }

    // ======================== 语义动态切分 ========================

    /**
     * <h3>拆分超长段落（语义切分 + 降级兜底）</h3>
     *
     * <h4>执行策略</h4>
     * <ol>
     *   <li>使用 {@link BreakIterator} 按句子分割段落</li>
     *   <li>尝试 {@link #semanticChunk} 语义切分（基于句子向量相似度）</li>
     *   <li>如果嵌入模型不可用或语义切分异常，
     *       自动降级为 {@link #splitLongParagraphByTokenFallback} 纯 token 切分</li>
     * </ol>
     *
     * @param header        标题头（每个 Chunk 都包含）
     * @param paragraph     待拆分的超长段落文本
     * @param contextPrefix 上下文前缀路径
     * @return 拆分后的 Chunk 列表
     */
    private List<Chunk> splitLongParagraph(String header, String paragraph,
                                           String contextPrefix) {
        List<Chunk> chunks = new ArrayList<>();

        // 步骤1：使用 BreakIterator 按句子分割
        // BreakIterator 是 JDK 内置的文本边界检测器，支持按句子/单词/字符分割
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(paragraph);
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        int end = iterator.next();
        while (end != BreakIterator.DONE) {
            String sentence = paragraph.substring(start, end).strip();
            if (!sentence.isEmpty()) sentences.add(sentence);
            start = end;
            end = iterator.next();
        }

        // 空段落兜底
        if (sentences.isEmpty()) {
            sentences.add(paragraph.strip());
        }
        if (sentences.isEmpty()) return chunks;

        // 步骤2：尝试语义切分
        try {
            if (embeddingModel != null) {
                return semanticChunk(header, sentences, contextPrefix);
            }
        } catch (Exception e) {
            log.warn("语义切分异常，降级为 token 切分。错误: {}", e.getMessage());
        }

        // 步骤3：降级为纯 token 切分
        return splitLongParagraphByTokenFallback(header, sentences, contextPrefix);
    }

    /**
     * <h3>语义切分核心实现</h3>
     *
     * <h4>算法原理</h4>
     * <p>维护一个<b>累积向量</b>（accumulatedVector），代表当前 Chunk 中所有句子的平均向量。
     * 遍历每个句子，计算其与累积向量的余弦相似度：</p>
     * <ul>
     *   <li>相似度 < semanticThreshold → 判定为<b>语义转折</b>，触发切分</li>
     *   <li>token 数超过 maxTokens → 触发切分</li>
     *   <li>否则 → 将句子加入当前 Chunk，更新累积向量</li>
     * </ul>
     *
     * <h4>累积向量更新公式（运行均值）</h4>
     * <pre>
     * new_vector[i] = (old_vector[i] × count + sentence_vector[i]) / (count + 1)
     * </pre>
     * <p>这是在线计算均值的标准方法，避免存储所有历史向量，内存效率 O(d) 而非 O(n×d)。</p>
     *
     * <p>独立方法便于异常隔离，异常由外层 {@link #splitLongParagraph} 捕获并降级。</p>
     *
     * @param header        标题头（每个 Chunk 都包含）
     * @param sentences     已分割的句子列表
     * @param contextPrefix 上下文前缀路径
     * @return 语义切分后的 Chunk 列表
     */
    private List<Chunk> semanticChunk(String header, List<String> sentences,
                                      String contextPrefix) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder(header);
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);
        List<String> currentSentences = new ArrayList<>();
        // 累积向量：当前 Chunk 中所有句子的平均向量
        float[] accumulatedVector = null;

        for (String sentence : sentences) {
            int sentenceTokens = tokenEstimator.estimateTokenCountInText(sentence);

            if (currentSentences.isEmpty()) {
                // 第一个句子：直接加入，初始化累积向量
                currentSentences.add(sentence);
                currentChunk.append(sentence);
                currentTokens += sentenceTokens;
                accumulatedVector = embedSentence(sentence);
                if (accumulatedVector == null) {
                    // 无法获取向量，抛异常由外层降级
                    throw new RuntimeException("无法获取句子向量");
                }
                continue;
            }

            boolean wouldExceedTokens = (currentTokens + sentenceTokens > maxTokens);
            boolean isSemanticShift = false;

            if (!wouldExceedTokens) {
                // 未超 token 限制，检查是否有语义转折
                float[] sentenceVec = embedSentence(sentence);
                if (sentenceVec != null) {
                    double similarity = cosineSimilarity(
                            accumulatedVector, sentenceVec);
                    if (similarity < semanticThreshold) {
                        isSemanticShift = true;
                        log.debug("语义转折: 相似度 {} < 阈值 {}",
                                String.format("%.3f", similarity),
                                semanticThreshold);
                    }
                } else {
                    // 某句向量获取失败，安全降级：强制触发切分
                    isSemanticShift = true;
                    log.warn("句子向量获取失败，强制切分");
                }
            }

            if (wouldExceedTokens || isSemanticShift) {
                // ===== 触发切分 =====
                if (currentChunk.length() > header.length()) {
                    chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                            currentTokens, contextPrefix));
                }
                // 重置累积器，开始新的 Chunk
                currentChunk = new StringBuilder(header);
                currentSentences.clear();
                currentChunk.append(sentence);
                currentTokens = tokenEstimator.estimateTokenCountInText(header)
                        + sentenceTokens;
                currentSentences.add(sentence);
                accumulatedVector = embedSentence(sentence);
                if (accumulatedVector == null) {
                    throw new RuntimeException("重新初始化向量失败");
                }
            } else {
                // ===== 正常累积 =====
                float[] sentenceVec = embedSentence(sentence);
                if (sentenceVec != null) {
                    // 更新累积向量为运行均值
                    int count = currentSentences.size();
                    for (int i = 0; i < accumulatedVector.length; i++) {
                        accumulatedVector[i] =
                                (accumulatedVector[i] * count + sentenceVec[i])
                                        / (count + 1);
                    }
                }
                // 若句子向量为 null，跳过更新累积向量，但句子仍加入当前块
                currentSentences.add(sentence);
                currentChunk.append(sentence);
                currentTokens += sentenceTokens;
            }
        }

        // 输出最后一个未完成的 Chunk
        if (currentChunk.length() > header.length()) {
            chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                    currentTokens, contextPrefix));
        }
        return chunks;
    }

    /**
     * <h3>对单个句子生成嵌入向量</h3>
     *
     * <p>调用嵌入模型将句子转为向量，失败时返回 null 而非抛异常，
     * 由调用方根据返回值决定降级策略。</p>
     *
     * @param sentence 句子文本
     * @return 句子的向量表示，失败时返回 null
     */
    private float[] embedSentence(String sentence) {
        try {
            var response = embeddingModel.embed(sentence);
            if (response != null && response.content() != null
                    && response.content().vector() != null) {
                return response.content().vector().clone();
            }
        } catch (Exception e) {
            log.warn("Embedding 调用失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * <h3>计算两个向量的余弦相似度</h3>
     *
     * <pre>
     * cosine_similarity = (A · B) / (||A|| × ||B||)
     *
     * 取值区间 [-1, 1]，值越大表示语义越相似
     * </pre>
     *
     * @param vec1 向量1
     * @param vec2 向量2
     * @return 余弦相似度（0.0 ~ 1.0 之间），任一向量为空时返回 0.0
     */
    private double cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length == 0) return 0.0;
        double dotProduct = 0.0, norm1 = 0.0, norm2 = 0.0;
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        return (norm1 == 0 || norm2 == 0) ? 0.0
                : dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * <h3>纯 Token 降级切分</h3>
     *
     * <p>不涉及任何语义判断，仅根据 token 数进行切分。
     * 当嵌入模型不可用或语义切分异常时使用。</p>
     *
     * <p>当当前累积 token 数 + 下一句 token 数 > maxTokens 时触发切分。</p>
     *
     * @param header        标题头
     * @param sentences     句子列表
     * @param contextPrefix 上下文前缀路径
     * @return 按 token 数切分的 Chunk 列表
     */
    private List<Chunk> splitLongParagraphByTokenFallback(
            String header, List<String> sentences, String contextPrefix) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);

        for (String sentence : sentences) {
            if (sentence.isBlank()) continue;
            int sTokens = tokenEstimator.estimateTokenCountInText(sentence);

            // 加上当前句子会超限 → 先输出当前 Chunk
            if (currentTokens + sTokens > maxTokens
                    && current.length() > header.length()) {
                chunks.add(createChunk(current.toString().stripTrailing(),
                        currentTokens, contextPrefix));
                current = new StringBuilder(header);
                currentTokens = tokenEstimator.estimateTokenCountInText(header);
            }
            current.append(sentence);
            currentTokens += sTokens;
        }

        // 输出最后一个未完成的 Chunk
        if (current.length() > header.length()) {
            chunks.add(createChunk(current.toString().stripTrailing(),
                    currentTokens, contextPrefix));
        }
        return chunks;
    }

    // ======================== 合并小 Chunk ========================

    /**
     * <h3>合并低于 minTokens 的小片段</h3>
     *
     * <p>遍历 Chunk 列表，将 token 数低于 minTokens 的 Chunk 与后续 Chunk 合并。</p>
     *
     * <h4>合并条件（三者必须同时满足）</h4>
     * <ul>
     *   <li>累加器 token 数 < minTokens</li>
     *   <li>合并后总 token 数 ≤ maxTokens</li>
     *   <li>两个 Chunk 的 contextPrefix 相同（同一层级）</li>
     * </ul>
     *
     * <p>合并方式：用 {@code \n\n} 连接两个 Chunk 的文本。</p>
     *
     * @param chunks 原始 Chunk 列表
     * @return 合并后的 Chunk 列表
     */
    private List<Chunk> mergeSmallChunks(List<Chunk> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<Chunk> merged = new ArrayList<>();
        Chunk accumulator = null;

        for (Chunk chunk : chunks) {
            if (accumulator == null) {
                // 初始化累加器
                accumulator = chunk;
            } else if (accumulator.tokenCount < minTokens
                    && accumulator.tokenCount + chunk.tokenCount <= maxTokens
                    && accumulator.contextPrefix.equals(chunk.contextPrefix)) {
                // 合并条件满足：用 \n\n 连接两个 Chunk
                String combinedText = accumulator.text + "\n\n" + chunk.text;
                accumulator = new Chunk(combinedText,
                        tokenEstimator.estimateTokenCountInText(combinedText),
                        accumulator.contextPrefix);
            } else {
                // 不满足合并条件：输出累加器，用当前 Chunk 作为新累加器
                merged.add(accumulator);
                accumulator = chunk;
            }
        }

        // 输出最后一个累加器
        if (accumulator != null) merged.add(accumulator);
        return merged;
    }

    // ======================== 重叠处理 ========================

    /**
     * <h3>在相邻 Chunk 之间添加重叠内容</h3>
     *
     * <h4>算法</h4>
     * <ol>
     *   <li>计算重叠 token 数：{@code ceil(prev.tokenCount × overlapRatio)}</li>
     *   <li>限制重叠量不超过 {@code maxTokens - curr.tokenCount}（保证不超限）</li>
     *   <li>调用 {@link #extractTailByText} 从前一个 Chunk 尾部提取段落级文本</li>
     *   <li>将提取的文本插入到当前 Chunk 的开头</li>
     * </ol>
     *
     * <h4>为什么需要重叠？</h4>
     * <p>检索时，如果用户查询恰好落在两个 Chunk 的边界处，可能丢失关键信息。
     * 重叠策略确保相邻 Chunk 之间有部分内容重复，减少边界丢失。</p>
     *
     * @param chunks 原始 Chunk 列表（已合并小片段）
     * @return 添加重叠后的 Chunk 列表
     */
    private List<Chunk> applyOverlap(List<Chunk> chunks) {
        if (chunks.size() <= 1 || overlapRatio <= 0) return chunks;

        List<Chunk> result = new ArrayList<>();
        result.add(chunks.get(0));  // 第一个 Chunk 不需要添加重叠

        for (int i = 1; i < chunks.size(); i++) {
            Chunk prev = chunks.get(i - 1);
            Chunk curr = chunks.get(i);

            // 计算重叠 token 数
            int overlapTokens = (int) Math.ceil(prev.tokenCount * overlapRatio);

            // 限制重叠量不超过 maxTokens - curr.tokenCount
            int maxAllowedOverlap = maxTokens - curr.tokenCount;
            if (maxAllowedOverlap <= 0) {
                // 当前 Chunk 已满，无法添加重叠
                result.add(curr);
                continue;
            }
            overlapTokens = Math.min(overlapTokens, maxAllowedOverlap);

            // 从 prev 尾部提取段落级文本
            String overlapText = extractTailByText(prev.text, overlapTokens);
            if (!overlapText.isEmpty()) {
                // 将重叠文本插入到当前 Chunk 开头
                String newText = overlapText + "\n" + curr.text;
                result.add(new Chunk(newText,
                        tokenEstimator.estimateTokenCountInText(newText),
                        curr.contextPrefix));
            } else {
                result.add(curr);
            }
        }
        return result;
    }

    /**
     * <h3>从文本尾部按段落提取指定 token 数量的内容</h3>
     *
     * <p>从文本末尾开始，按段落逐段向前提取，直到累计 token 数达到目标值。</p>
     *
     * <h4>为什么按段落而不是按句子？</h4>
     * <p>按段落提取保证重叠内容在语义边界处截断，避免出现半个句子或半个段落。</p>
     *
     * <h4>段落分隔符</h4>
     * <ul>
     *   <li>{@code \n\n} — 空行分隔</li>
     *   <li>{@code \n---\n} — 水平线分隔</li>
     *   <li>{@code \n***\n} — 星号分隔线</li>
     * </ul>
     *
     * @param text         原始文本
     * @param targetTokens 目标 token 数
     * @return 从尾部提取的文本（不超过 targetTokens）
     */
    private String extractTailByText(String text, int targetTokens) {
        if (targetTokens <= 0) return "";

        // 按段落分隔符拆分文本
        String[] paragraphs = text.split(
                "\\n\\s*\\n|\\n\\s*---\\s*\\n|\\n\\s*\\*\\*\\*\\s*\\n");

        StringBuilder tail = new StringBuilder();
        int tokens = 0;

        // 从最后一个段落开始，逐段向前提取
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String para = paragraphs[i].strip();
            if (para.isEmpty()) continue;

            int paraTokens = tokenEstimator.estimateTokenCountInText(para);
            // 超过目标且已有内容 → 停止
            if (tokens + paraTokens > targetTokens && tail.length() > 0) break;

            // 插入到开头（因为是从后往前提取）
            if (tail.length() == 0) {
                tail.insert(0, para);
            } else {
                tail.insert(0, para + "\n\n");
            }
            tokens += paraTokens;
        }
        return tail.toString().strip();
    }

    /**
     * <h3>构建上下文前缀路径</h3>
     *
     * <p>将当前前缀与 Section 标题级联，形成层级路径。</p>
     *
     * <h4>示例</h4>
     * <pre>
     * currentPrefix = "第一章"
     * section.title = "## 1.1 概述"
     *
     * 结果: "第一章 > 1.1 概述"
     * </pre>
     *
     * <p>标题中的 {@code #} 前缀会被移除，只保留纯文本标题。</p>
     *
     * @param currentPrefix 当前上下文前缀
     * @param section       当前 Section
     * @return 级联后的上下文前缀
     */
    private String buildContextPrefix(String currentPrefix, Section section) {
        if (section.title.isEmpty()) return currentPrefix;
        // 移除标题中的 # 前缀
        String titleText = section.title.replaceFirst("^#+\\s+", "").strip();
        return currentPrefix.isEmpty()
                ? titleText
                : currentPrefix + " > " + titleText;
    }

    // ======================== 内部数据结构 ========================

    /**
     * <h3>内容块区间记录</h3>
     *
     * <p>记录特殊块（代码块/表格）在原文中的位置区间和文本内容。</p>
     *
     * @param start        起始位置
     * @param end          结束位置
     * @param text         块文本
     * @param isSplittable 是否可拆分（代码块/表格=false，段落=true）
     */
    record BlockSpan(int start, int end, String text, boolean isSplittable) {}

    /**
     * <h3>内容块</h3>
     *
     * <p>Section 内容拆分后的最小处理单元。</p>
     *
     * @param text         块文本
     * @param isSplittable 是否可拆分（代码块/表格=false，段落=true）
     */
    static class ContentBlock {
        final String text;
        final boolean isSplittable;

        ContentBlock(String text, boolean isSplittable) {
            this.text = text;
            this.isSplittable = isSplittable;
        }
    }

    /**
     * <h3>切片结果（Chunk）</h3>
     *
     * <p>文档切片的最终产物，包含文本、token 数和上下文路径。</p>
     *
     * <h4>字段说明</h4>
     * <ul>
     *   <li><b>text</b>：切片文本（含上下文前缀标记）</li>
     *   <li><b>tokenCount</b>：切片 token 数</li>
     *   <li><b>contextPrefix</b>：层级路径（如 "第一章 > 1.1 概述"），
     *       用于检索时展示文档来源</li>
     * </ul>
     */
    public static class Chunk {
        private final String text;
        private final int tokenCount;
        private final String contextPrefix;

        public Chunk(String text, int tokenCount, String contextPrefix) {
            this.text = text;
            this.tokenCount = tokenCount;
            this.contextPrefix = contextPrefix;
        }

        public String text() { return text; }
        public int tokenCount() { return tokenCount; }
        public String contextPrefix() { return contextPrefix; }

        @Override
        public String toString() {
            return "Chunk{tokens=" + tokenCount +
                    ", prefix='" + contextPrefix + "'" +
                    ", text='" + text.substring(0, Math.min(80, text.length()))
                    + "...'}";
        }
    }
}