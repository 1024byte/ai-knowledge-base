package com.hai.aiknowledgebase.service;

import dev.langchain4j.model.TokenCountEstimator;
import lombok.extern.slf4j.Slf4j;

import java.text.BreakIterator;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 Markdown 标题层级和段落边界的递归文档切片器。
 * <p>
 * 核心规则：
 * <ul>
 *   <li>顺着标题层级和段落边界切</li>
 *   <li>标题跟着它管理的内容走</li>
 *   <li>表格尽量整块保留成 Markdown</li>
 *   <li>不拆散内嵌对象</li>
 *   <li>400–8008 token 一块，留 20% 重叠</li>
 *   <li>上下文不断裂，语义被完整保留</li>
 *   <li>递归切片</li>
 * </ul>
 */
@Slf4j
public class MarkdownDocumentChunker {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(^\\|.+)\\n(\\|?[-:| ]+)\\n((?:\\|.+\\n?)+)",
            Pattern.MULTILINE
    );
/*    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "(```[\\s\\S]*?```)",
            Pattern.MULTILINE
    );*/

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```[a-zA-Z0-9_]*\\s*[\\s\\S]*?```",  // 支持语言标识
            Pattern.MULTILINE
    );

    /** 最小 chunk token 数 */
    private final int minTokens;
    /** 最大 chunk token 数 */
    private final int maxTokens;
    /** 重叠比例 */
    private final double overlapRatio;
    /** Token 计算*/
    private final TokenCountEstimator tokenEstimator;


    public MarkdownDocumentChunker(int minTokens, int maxTokens, double overlapRatio,
                                   TokenCountEstimator tokenEstimator) {
        this.minTokens = minTokens;
        this.maxTokens = maxTokens;
        this.overlapRatio = overlapRatio;
        this.tokenEstimator = tokenEstimator;
    }

    // ======================== 公开 API ========================

    /**
     * 将 Markdown 文本递归切片为多个 chunk。
     *
     * @param markdown 原始 Markdown 文本
     * @return 切片结果列表，每个 chunk 带有 token 估算值
     */
    public List<Chunk> chunk(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        // 1. 解析为 section 树
        List<Section> sections = parseSections(markdown);

        // 2. 递归切片
        List<Chunk> rawChunks = recursiveChunk(sections, "");

        // 3. 添加重叠
        List<Chunk> overlappedChunks = applyOverlap(rawChunks);

        log.debug("切片完成: {} 个 section → {} 个原始 chunk → {} 个重叠 chunk",
                sections.size(), rawChunks.size(), overlappedChunks.size());
        return overlappedChunks;
    }

    // ======================== Section 解析 ========================

    /**
     * Markdown section 节点：标题 + 其管辖的内容（含子 section）
     */
    static class Section {
        int level;           // 标题级别 1-6，0 表示无标题的顶层内容
        String title;        // 标题文本（含 # 前缀），无标题时为空
        String content;      // 标题下的直属内容（不含子 section）
        List<Section> children = new ArrayList<>();

        Section(int level, String title, String content) {
            this.level = level;
            this.title = title;
            this.content = content;
        }

        /** 完整文本 = 标题 + 直属内容 + 所有子 section */
        String fullText() {
            StringBuilder sb = new StringBuilder();
            if (!title.isEmpty()) {
                sb.append(title).append("\n");
            }
            if (!content.isEmpty()) {
                sb.append(content).append("\n");
            }
            for (Section child : children) {
                sb.append(child.fullText()).append("\n");
            }
            return sb.toString().stripTrailing();
        }

        /** 直属内容 token 数（不含子 section） */
        int ownTokenCount(TokenCountEstimator estimator) {
            int tokens = 0;
            if (!title.isEmpty()) {
                tokens += estimator.estimateTokenCountInText(title);
            }
            if (!content.isEmpty()) {
                tokens += estimator.estimateTokenCountInText(content);
            }
            return tokens;
        }

        /** 完整 token 数（含子 section） */
        int totalTokenCount(TokenCountEstimator estimator) {
            int tokens = ownTokenCount(estimator);
            for (Section child : children) {
                tokens += child.totalTokenCount(estimator);
            }
            return tokens;
        }
    }

    /**
     * 将 Markdown 解析为 section 树。
     * 标题跟着它管理的内容走：每个 section 包含标题 + 到下一个同级/更高级标题之间的内容。
     */
    List<Section> parseSections(String markdown) {
        List<Section> sections = new ArrayList<>();
        List<HeadingInfo> headings = findHeadings(markdown);

        if (headings.isEmpty()) {
            // 无标题，整篇作为一个 level-0 section
            sections.add(new Section(0, "", markdown.strip()));
            return sections;
        }

        // 如果第一个标题前有内容，作为 level-0 section
        int firstHeadingStart = headings.get(0).start;
        if (firstHeadingStart > 0) {
            String preamble = markdown.substring(0, firstHeadingStart).strip();
            if (!preamble.isEmpty()) {
                sections.add(new Section(0, "", preamble));
            }
        }

        // 为每个标题构建 section
        for (int i = 0; i < headings.size(); i++) {
            HeadingInfo heading = headings.get(i);
            int contentStart = heading.end;
            int contentEnd = (i + 1 < headings.size()) ? headings.get(i + 1).start : markdown.length();
            String content = markdown.substring(contentStart, contentEnd).strip();

            Section section = new Section(heading.level, heading.fullLine, content);
            sections.add(section);
        }

        // 构建树形结构：将子 section 挂到父 section 下
        return buildSectionTree(sections);
    }

    /** 标题位置信息 */
    record HeadingInfo(int level, String fullLine, int start, int end) {}

    private List<HeadingInfo> findHeadings(String markdown) {
        List<HeadingInfo> headings = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(markdown);
        while (matcher.find()) {
            int level = matcher.group(1).length();
            String fullLine = matcher.group(0);
            int start = matcher.start();
            int end = matcher.end();
            headings.add(new HeadingInfo(level, fullLine, start, end));
        }
        return headings;
    }

    /**
     * 将扁平 section 列表构建为树形结构。
     * 规则：每个 section 挂到前面最近的、level 比自己小的 section 下。
     */
    private List<Section> buildSectionTree(List<Section> flatSections) {
        List<Section> roots = new ArrayList<>();
        List<Section> stack = new ArrayList<>();  // 当前路径上的祖先

        for (Section section : flatSections) {
            // 弹出栈中 level >= 当前 section 的节点
            while (!stack.isEmpty() && stack.get(stack.size() - 1).level >= section.level) {
                stack.remove(stack.size() - 1);
            }

            if (stack.isEmpty()) {
                roots.add(section);
            } else {
                stack.get(stack.size() - 1).children.add(section);
            }
            stack.add(section);
        }
        return roots;
    }

    // ======================== 递归切片 ========================

    /**
     * 迭代切片核心逻辑（使用显式栈避免深层嵌套导致的栈溢出）：
     * - 如果 section 总 token 数 <= maxTokens，整块保留
     * - 否则，先尝试按子 section 边界切
     * - 如果单个子 section 仍然过大，继续迭代处理
     * - 如果无子 section 且直属内容过大，按段落/表格/代码块边界切
     * <p>
     * 使用 Deque 模拟递归调用栈。通过哨兵标记栈帧边界，
     * 确保 mergeSmallChunks 在正确的层级（同一父 section 的子 section 之间）合并。
     * 每个栈帧拥有独立的 chunk 收集器和上下文前缀，帧结束时合并后汇入父帧。
     */
    private List<Chunk> recursiveChunk(List<Section> sections, String contextPrefix) {
        // 栈帧哨兵：标记一个栈帧的边界，用于在正确的层级执行 mergeSmallChunks
        record FrameMarker(String contextPrefix) {}

        Deque<Object> stack = new ArrayDeque<>();
        // 初始任务：先压入哨兵（帧结束标记），再逆序压入各 section
        stack.push(new FrameMarker(contextPrefix));
        for (int i = sections.size() - 1; i >= 0; i--) {
            stack.push(sections.get(i));
        }

        // 每个栈帧对应一个 chunk 收集器和上下文前缀；栈底为最终结果
        Deque<List<Chunk>> chunkFrameStack = new ArrayDeque<>();
        Deque<String> prefixFrameStack = new ArrayDeque<>();
        chunkFrameStack.push(new ArrayList<>());
        prefixFrameStack.push(contextPrefix);

        while (!stack.isEmpty()) {
            Object item = stack.pop();

            if (item instanceof FrameMarker marker) {
                // 帧结束：合并当前帧的 chunk，汇入父帧
                List<Chunk> currentFrameChunks = chunkFrameStack.pop();
                prefixFrameStack.pop();
                List<Chunk> merged = mergeSmallChunks(currentFrameChunks);
                if (!chunkFrameStack.isEmpty()) {
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
                // 整块保留
                String text = section.fullText();
                if (!text.isBlank()) {
                    currentFrameChunks.add(new Chunk(text, tokenEstimator.estimateTokenCountInText(text), currentPrefix));
                }
            } else if (!section.children.isEmpty()) {
                // 有子 section，按子 section 边界切：压入新的栈帧
                String childPrefix = buildContextPrefix(currentPrefix, section);
                stack.push(new FrameMarker(childPrefix));
                for (int i = section.children.size() - 1; i >= 0; i--) {
                    stack.push(section.children.get(i));
                }
                chunkFrameStack.push(new ArrayList<>());
                prefixFrameStack.push(childPrefix);
            } else {
                // 无子 section，直属内容过大，按段落边界切
                currentFrameChunks.addAll(splitLargeSection(section, currentPrefix));
            }
        }

        return chunkFrameStack.isEmpty() ? List.of() : chunkFrameStack.pop();
    }

    /**
     * 对过大的 section 按段落/表格/代码块边界切分。
     */
    private List<Chunk> splitLargeSection(Section section, String contextPrefix) {
        String header = section.title.isEmpty() ? "" : section.title + "\n";
        String content = section.content;

        // 将内容按不可拆分块（表格、代码块）和普通段落拆分
        List<ContentBlock> blocks = splitIntoBlocks(content);

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder(header);
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);

        for (ContentBlock block : blocks) {
            int blockTokens = tokenEstimator.estimateTokenCountInText(block.text);

            if (blockTokens > maxTokens && !block.isSplittable) {
                // 不可拆分块（表格/代码块）超过上限，只能整块保留
                if (currentChunk.length() > header.length()) {
                    chunks.add(new Chunk(currentChunk.toString().stripTrailing(),
                            currentTokens, contextPrefix));
                }
                int headerTokens = tokenEstimator.estimateTokenCountInText(header);
                int totalChunkTokens = headerTokens + blockTokens;
                if (totalChunkTokens > maxTokens) {
                    log.warn("不可拆分块（表格/代码块）token 数 ({}) 超过 maxTokens ({})，"
                            + "可能导致后续向量化或检索异常，建议缩小该块体积",
                            totalChunkTokens, maxTokens);
                }
                chunks.add(new Chunk(header + block.text, totalChunkTokens, contextPrefix));
                currentChunk = new StringBuilder(header);
                currentTokens = headerTokens;
            } else if (blockTokens > maxTokens && block.isSplittable) {
                // 可拆分块（长段落）按句子再切
                if (currentChunk.length() > header.length()) {
                    chunks.add(new Chunk(currentChunk.toString().stripTrailing(),
                            currentTokens, contextPrefix));
                }
                chunks.addAll(splitLongParagraph(header, block.text, contextPrefix));
                currentChunk = new StringBuilder(header);
                currentTokens = tokenEstimator.estimateTokenCountInText(header);
            } else if (currentTokens + blockTokens > maxTokens) {
                // 加入当前块会超限，先输出当前 chunk
                chunks.add(new Chunk(currentChunk.toString().stripTrailing(),
                        currentTokens, contextPrefix));
                currentChunk = new StringBuilder(header);
                currentChunk.append(block.text).append("\n");
                currentTokens = tokenEstimator.estimateTokenCountInText(header) + blockTokens;
            } else {
                currentChunk.append(block.text).append("\n");
                currentTokens += blockTokens;
            }
        }

        if (currentChunk.length() > header.length()) {
            chunks.add(new Chunk(currentChunk.toString().stripTrailing(),
                    currentTokens, contextPrefix));
        }

        return chunks;
    }

    /**
     * 将内容拆分为不可拆分的块（表格、代码块）和普通段落。
     */
    private List<ContentBlock> splitIntoBlocks(String content) {
        List<ContentBlock> blocks = new ArrayList<>();

        // 先提取代码块和表格，标记位置
        List<BlockSpan> spans = new ArrayList<>();

        Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(content);
        while (codeMatcher.find()) {
            spans.add(new BlockSpan(codeMatcher.start(), codeMatcher.end(),
                    content.substring(codeMatcher.start(), codeMatcher.end()), false));
        }

        Matcher tableMatcher = TABLE_PATTERN.matcher(content);
        while (tableMatcher.find()) {
            // 检查是否与代码块重叠
            boolean overlaps = spans.stream().anyMatch(s ->
                    s.start < tableMatcher.end() && s.end > tableMatcher.start());
            if (!overlaps) {
                spans.add(new BlockSpan(tableMatcher.start(), tableMatcher.end(),
                        tableMatcher.group(0), false));
            }
        }

        spans.sort((a, b) -> Integer.compare(a.start, b.start));

        // 填充普通段落
        int cursor = 0;
        for (BlockSpan span : spans) {
            if (span.start > cursor) {
                String paragraphText = content.substring(cursor, span.start).strip();
                if (!paragraphText.isEmpty()) {
                    // 按空行拆分段落
                    String[] paragraphs = paragraphText.split("\\n\\s*\\n");
                    for (String para : paragraphs) {
                        String p = para.strip();
                        if (!p.isEmpty()) {
                            blocks.add(new ContentBlock(p + "\n", true));
                        }
                    }
                }
            }
            blocks.add(new ContentBlock(span.text + "\n", false));
            cursor = span.end;
        }

        // 尾部普通段落
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

        // 如果没有特殊块，按段落拆分
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

    /**
     * 对长段落按句子边界切分。
     */
    private List<Chunk> splitLongParagraph(String header, String paragraph, String contextPrefix) {
        List<Chunk> chunks = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.CHINESE);
        iterator.setText(paragraph);

        int start = iterator.first();
        int end = iterator.next();
        StringBuilder current = new StringBuilder(header);
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);

        while (end != BreakIterator.DONE) {
            String sentence = paragraph.substring(start, end).strip();
            if (!sentence.isEmpty()) {
                int sTokens = tokenEstimator.estimateTokenCountInText(sentence);
                if (currentTokens + sTokens > maxTokens && current.length() > header.length()) {
                    chunks.add(new Chunk(current.toString().stripTrailing(), currentTokens, contextPrefix));
                    current = new StringBuilder(header);
                    currentTokens = tokenEstimator.estimateTokenCountInText(header);
                }
                current.append(sentence);
                currentTokens += sTokens;
            }
            start = end;
            end = iterator.next();
        }

        if (current.length() > header.length()) {
            chunks.add(new Chunk(current.toString().stripTrailing(), currentTokens, contextPrefix));
        }
        return chunks;
    }

    // ======================== 合并小 chunk ========================

    /**
     * 合并过小的 chunk：如果相邻 chunk 合并后不超过 maxTokens，则合并。
     */
    private List<Chunk> mergeSmallChunks(List<Chunk> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<Chunk> merged = new ArrayList<>();
        Chunk accumulator = null;

        for (Chunk chunk : chunks) {
            if (accumulator == null) {
                accumulator = chunk;
            } else if (accumulator.tokenCount < minTokens
                    && accumulator.tokenCount + chunk.tokenCount <= maxTokens) {
                // 合并
                String combinedText = accumulator.text + "\n\n" + chunk.text;
                accumulator = new Chunk(combinedText,
                        tokenEstimator.estimateTokenCountInText(combinedText),
                        accumulator.contextPrefix);
            } else {
                merged.add(accumulator);
                accumulator = chunk;
            }
        }

        if (accumulator != null) {
            merged.add(accumulator);
        }

        return merged;
    }

    // ======================== 重叠处理 ========================

    /**
     * 为相邻 chunk 添加重叠内容。
     * 重叠量 = 前一个 chunk token 数 × overlapRatio
     * 从前一个 chunk 末尾取重叠文本，追加到当前 chunk 开头。
     * 重叠量会受 maxTokens 约束，确保追加后不超过上限。
     */
    private List<Chunk> applyOverlap(List<Chunk> chunks) {
        if (chunks.size() <= 1 || overlapRatio <= 0) {
            return chunks;
        }

        List<Chunk> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            Chunk prev = chunks.get(i - 1);
            Chunk curr = chunks.get(i);

            int overlapTokens = (int) Math.ceil(prev.tokenCount * overlapRatio);
            // 确保重叠后不超过 maxTokens：最多允许 (maxTokens - curr.tokenCount) 的重叠
            int maxAllowedOverlap = maxTokens - curr.tokenCount;
            if (maxAllowedOverlap <= 0) {
                // 当前 chunk 已达到或超过上限，不添加重叠
                result.add(curr);
                continue;
            }
            overlapTokens = Math.min(overlapTokens, maxAllowedOverlap);

            String overlapText = extractTailByText(prev.text, overlapTokens);

            if (!overlapText.isEmpty()) {
                String newText = overlapText + "\n" + curr.text;
                result.add(new Chunk(newText, tokenEstimator.estimateTokenCountInText(newText), curr.contextPrefix));
            } else {
                result.add(curr);
            }
        }

        return result;
    }

    /**
     * 从文本末尾提取约 targetTokens 个 token 的文本。
     * 按段落边界截取，避免截断语义。
     */
    private String extractTailByText(String text, int targetTokens) {
        if (targetTokens <= 0) return "";

        // 按段落拆分
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder tail = new StringBuilder();
        int tokens = 0;

        // 从后往前拼接段落
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String para = paragraphs[i].strip();
            if (para.isEmpty()) continue;

            int paraTokens = tokenEstimator.estimateTokenCountInText(para);
            if (tokens + paraTokens > targetTokens && tail.length() > 0) {
                break;
            }
            if (tail.length() == 0) {
                tail.insert(0, para);
            } else {
                tail.insert(0, para + "\n\n");
            }
            tokens += paraTokens;
        }

        return tail.toString().strip();
    }

    // ======================== 上下文前缀 ========================

    /**
     * 构建上下文前缀：父级标题链，用于在子 chunk 中保留层级上下文。
     */
    private String buildContextPrefix(String currentPrefix, Section section) {
        if (section.title.isEmpty()) return currentPrefix;
        String titleText = section.title.replaceFirst("^#+\\s+", "").strip();
        return currentPrefix.isEmpty() ? titleText : currentPrefix + " > " + titleText;
    }

    // ======================== 内部数据结构 ========================

    record BlockSpan(int start, int end, String text, boolean isSplittable) {}

    static class ContentBlock {
        final String text;
        final boolean isSplittable;  // true=普通段落可再切, false=表格/代码块不可拆

        ContentBlock(String text, boolean isSplittable) {
            this.text = text;
            this.isSplittable = isSplittable;
        }
    }

    // ======================== Chunk 结果 ========================

    /**
     * 切片结果：一个语义完整的文本块。
     */
    public static class Chunk {
        /** chunk 文本内容 */
        private final String text;
        /** 估算 token 数 */
        private final int tokenCount;
        /** 上下文前缀（父级标题链） */
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
                    ", text='" + text.substring(0, Math.min(80, text.length())) + "...'}";
        }
    }

}
