package com.hai.aiknowledgebase.service;

import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

import com.hai.aiknowledgebase.common.TextTokenUtils;
import com.hai.aiknowledgebase.config.ChunkingConfig;

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
 *   <li><b>重叠策略</b>：相邻 Chunk 之间按固定 overlapTokens 添加尾部重叠，防止边界信息丢失</li>
 *   <li><b>上下文前缀</b>：每个 Chunk 携带层级路径（如 "第一章 > 1.1 概述"），增强检索可解释性</li>
 * </ol>
 *
 * <h3>重叠预留机制</h3>
 * <p>切片阶段使用 {@code effectiveMaxTokens = maxTokens - overlapTokens} 作为目标上限，
 * 为 {@link #applyOverlap} 预留空间。否则 chunk 贴近 maxTokens 时
 * {@code maxTokens - curr.tokenCount} 趋近 0，重叠会被压缩甚至跳过。</p>
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

    /**
     * 匹配编号子标题模式，用于检测"同级别编号子标题"场景。
     */
    private static final Pattern NUMBERED_HEADING_PATTERN = Pattern.compile(
            "^(#{1,6})\\s+" +
                    "(" +
                    "\\d{1,3}[．、\\.）\\)]\\s?" +
                    "|" +
                    "[一二三四五六七八九十]{1,3}[、．\\.]\\s?" +
                    "|" +
                    "[（\\(]\\s*(\\d{1,3}|[一二三四五六七八九十]{1,3})\\s*[）\\)]\\s?" +
                    ")" +
                    ".+$",
            Pattern.MULTILINE
    );

    /** 上下文前缀标记，用于剥离前缀提取正文 */
    private static final String CONTEXT_PREFIX_MARKER = "**上下文**: ";

    /** 有意义的重叠最小 token 阈值，低于此值视为配置不合理 */
    private static final int MIN_MEANINGFUL_OVERLAP = 10;

    // ======================== 配置参数 ========================

    /** 每个 Chunk 的最小 token 数，低于此值的 Chunk 会尝试与相邻 Chunk 合并 */
    private final int minTokens;

    /** 每个 Chunk 的最大 token 数（硬约束，仅重叠后仍不应超太多） */
    private final int maxTokens;

    /** 相邻 Chunk 之间的重叠 token 数（固定值），0 表示不重叠 */
    private final int overlapTokens;

    /**
     * 有效切分上限：{@code maxTokens - overlapTokens}。
     *
     * <p>切片阶段用此值作为目标上限，为后续 {@link #applyOverlap} 添加重叠预留空间。
     * 若仍按 maxTokens 切满，chunk 贴近上限后 {@code maxTokens - curr.tokenCount} 趋近 0，
     * 重叠会被压缩甚至跳过（日志中的 "已满/空间不足" 即此原因）。</p>
     *
     * <p>保底不小于 {@link #minTokens}；若配置不合理（overlap &ge; maxTokens），
     * 退化为 maxTokens 的一半。</p>
     */
    private final int effectiveMaxTokens;

    /** Token 计数估算器 */
    private final TokenCountEstimator tokenEstimator;

    /** 嵌入模型，可为 null（降级为纯 token 切分） */
    private final EmbeddingModel embeddingModel;

    /** 语义切分相似度阈值 */
    private final double semanticThreshold;

    /**
     * 构造 Markdown 文档切片器。
     */
    public MarkdownDocumentChunker(int minTokens, int maxTokens, int overlapTokens,
                                   TokenCountEstimator tokenEstimator,
                                   EmbeddingModel embeddingModel,
                                   double semanticThreshold) {
        this.minTokens = minTokens;
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
        this.tokenEstimator = tokenEstimator;
        this.embeddingModel = embeddingModel;
        this.semanticThreshold = semanticThreshold;

        // 预留重叠空间：切片目标上限 = maxTokens - overlapTokens
        int reserved = Math.max(0, overlapTokens);
        int candidate = maxTokens - reserved;
        if (candidate < minTokens) {
            candidate = Math.max(minTokens, maxTokens / 2);
        }
        this.effectiveMaxTokens = Math.min(candidate, maxTokens);
        log.info("切片器初始化: minTokens={}, maxTokens={}, overlapTokens={}, effectiveMaxTokens={}",
                minTokens, maxTokens, overlapTokens, this.effectiveMaxTokens);
    }

    // ======================== 主入口 ========================

    /**
     * <h3>对 Markdown 文档执行智能切片</h3>
     */
    public List<Chunk> chunk(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        // 步骤1：解析 Markdown 标题层级，构建 Section 树
        List<Section> sections = parseSections(markdown);
        // 步骤2：栈式迭代遍历 Section 树，生成原始 Chunk 列表
        List<Chunk> rawChunks = iterativeChunk(sections, "");
        // 步骤3+4：合并小片段 + 添加重叠
        List<Chunk> overlappedChunks = applyOverlap(rawChunks);
        // 步骤5：预计算父上下文
        computeParentText(overlappedChunks);

        log.debug("切片完成: {} 个 section → {} 个原始 chunk → {} 个重叠 chunk",
                sections.size(), rawChunks.size(), overlappedChunks.size());
        return overlappedChunks;
    }

    // ======================== 父上下文计算 ========================

    private void computeParentText(List<Chunk> chunks) {
        int window = ChunkingConfig.PARENT_WINDOW;

        String[] sectionKeys = new String[chunks.size()];
        String currentSection = null;
        for (int i = 0; i < chunks.size(); i++) {
            String key = extractTopSectionKey(chunks.get(i).text());
            if (key != null) {
                currentSection = key;
            }
            sectionKeys[i] = currentSection;
        }

        for (int i = 0; i < chunks.size(); i++) {
            String mySection = sectionKeys[i];

            int start = i;
            for (int s = i - 1; s >= 0 && s >= i - window; s--) {
                if (sectionKeys[s] != null && mySection != null
                        && !sectionKeys[s].equals(mySection)) {
                    break;
                }
                start = s;
            }

            int end = i + 1;
            for (int e = i + 1; e < chunks.size() && e <= i + window; e++) {
                if (sectionKeys[e] != null && mySection != null
                        && !sectionKeys[e].equals(mySection)) {
                    break;
                }
                end = e;
            }

            String parentText = buildParentTextWithoutOverlap(chunks, start, end);

            chunks.get(i).metadata().put("parent_text", parentText);
            chunks.get(i).metadata().put("parent_start", String.valueOf(start));
            chunks.get(i).metadata().put("parent_end", String.valueOf(end - 1));
        }
    }

    private String extractTopSectionKey(String text) {
        Matcher m = HEADING_PATTERN.matcher(text);
        if (m.find()) {
            int level = m.group(1).length();
            if (level <= 2) {
                return m.group(2).trim();
            }
        }
        return null;
    }

    private String buildParentTextWithoutOverlap(List<Chunk> chunks, int start, int end) {
        StringBuilder parentText = new StringBuilder();
        String prevText = null;

        for (int j = start; j < end; j++) {
            String currText = chunks.get(j).body();

            if (prevText != null) {
                String overlap = findOverlapSuffix(prevText, currText);
                if (!overlap.isEmpty()) {
                    currText = currText.substring(overlap.length());
                }
                parentText.append("\n\n");
            }

            parentText.append(currText);
            prevText = chunks.get(j).body();
        }

        return parentText.toString();
    }

    private String findOverlapSuffix(String text1, String text2) {
        if (text1 == null || text2 == null || text1.isEmpty() || text2.isEmpty()) {
            return "";
        }
        String[] paragraphs = text1.split("(?<=\\n\\n)");
        StringBuilder suffix = new StringBuilder();
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            suffix.insert(0, paragraphs[i]);
            String candidate = suffix.toString().strip();
            if (candidate.isEmpty()) continue;
            if (text2.stripLeading().startsWith(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    // ======================== Section 数据结构 ========================

    static class Section {
        int level;
        String title;
        String content;
        List<Section> children = new ArrayList<>();

        Section(int level, String title, String content) {
            this.level = level;
            this.title = title;
            this.content = content;
        }

        String fullText() {
            StringBuilder sb = new StringBuilder();
            if (!title.isEmpty()) sb.append(title).append("\n");
            if (!content.isEmpty()) sb.append(content).append("\n");
            for (Section child : children) {
                sb.append(child.fullText()).append("\n");
            }
            return sb.toString().stripTrailing();
        }

        int ownTokenCount(TokenCountEstimator estimator) {
            int tokens = 0;
            if (!title.isEmpty()) tokens += estimator.estimateTokenCountInText(title);
            if (!content.isEmpty()) tokens += estimator.estimateTokenCountInText(content);
            return tokens;
        }

        int totalTokenCount(TokenCountEstimator estimator) {
            int tokens = ownTokenCount(estimator);
            for (Section child : children) {
                tokens += child.totalTokenCount(estimator);
            }
            return tokens;
        }
    }

    // ======================== Section 解析 ========================

    List<Section> parseSections(String markdown) {
        List<Section> sections = new ArrayList<>();
        List<HeadingInfo> headings = findHeadings(markdown);

        if (headings.isEmpty()) {
            sections.add(new Section(0, "", markdown.strip()));
            return sections;
        }

        int firstHeadingStart = headings.get(0).start;
        if (firstHeadingStart > 0) {
            String preamble = markdown.substring(0, firstHeadingStart).strip();
            if (!preamble.isEmpty()) {
                sections.add(new Section(0, "", preamble));
            }
        }

        for (int i = 0; i < headings.size(); i++) {
            HeadingInfo heading = headings.get(i);
            int contentStart = heading.end;
            int contentEnd = (i + 1 < headings.size())
                    ? headings.get(i + 1).start : markdown.length();
            String content = markdown.substring(contentStart, contentEnd).strip();
            sections.add(new Section(heading.level, heading.fullLine, content));
        }

        normalizeNumberedSiblingHeadings(sections);

        return buildSectionTree(sections);
    }

    private void normalizeNumberedSiblingHeadings(List<Section> sections) {
        if (sections.size() < 2) return;

        for (int i = 0; i < sections.size() - 1; i++) {
            Section current = sections.get(i);

            if (isNumberedHeading(current.title)) continue;
            if (!current.content.isBlank() && current.content.trim().length() > minTokens) continue;
            if (current.level == 0) continue;

            int j = i + 1;
            if (sections.get(j).level != current.level) continue;
            if (!isNumberedHeading(sections.get(j).title)) continue;

            int end = j;
            while (end < sections.size()
                    && sections.get(end).level == current.level
                    && isNumberedHeading(sections.get(end).title)) {
                end++;
            }

            int numberedCount = end - j;
            if (numberedCount < 2) continue;

            int newLevel = Math.min(current.level + 1, 6);
            log.debug("检测到同级别编号子标题模式: 父='{}' (L{}), 子标题数={}, 提升至 L{}",
                    current.title.replaceAll("^#+\\s+", ""),
                    current.level, numberedCount, newLevel);

            for (int k = j; k < end; k++) {
                Section child = sections.get(k);
                child.level = newLevel;
                child.title = "#".repeat(newLevel) + " " +
                        child.title.replaceFirst("^#+\\s+", "");
            }

            i = end - 1;
        }
    }

    private boolean isNumberedHeading(String headingText) {
        if (headingText == null || headingText.isBlank()) return false;
        if (!NUMBERED_HEADING_PATTERN.matcher(headingText).matches()) return false;

        String titlePart = headingText.replaceFirst("^#+\\s+", "");
        if (titlePart.matches("\\d{1,3}[．、\\.）\\)]\\s*\\d{1,3}[．、\\.].*")) {
            return false;
        }
        return true;
    }

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

    private List<Section> buildSectionTree(List<Section> flatSections) {
        List<Section> roots = new ArrayList<>();
        List<Section> stack = new ArrayList<>();

        for (Section section : flatSections) {
            while (!stack.isEmpty()
                    && stack.get(stack.size() - 1).level >= section.level) {
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

    // ======================== 迭代切片 ========================

    private List<Chunk> iterativeChunk(List<Section> sections, String contextPrefix) {
        record FrameMarker(String contextPrefix) {}

        Deque<Object> stack = new ArrayDeque<>();
        stack.push(new FrameMarker(contextPrefix));
        for (int i = sections.size() - 1; i >= 0; i--) {
            stack.push(sections.get(i));
        }

        Deque<List<Chunk>> chunkFrameStack = new ArrayDeque<>();
        Deque<String> prefixFrameStack = new ArrayDeque<>();
        chunkFrameStack.push(new ArrayList<>());
        prefixFrameStack.push(contextPrefix);

        while (!stack.isEmpty()) {
            Object item = stack.pop();

            if (item instanceof FrameMarker marker) {
                if (!prefixFrameStack.peek().equals(marker.contextPrefix)) {
                    log.warn("帧上下文不匹配，预期: {}, 实际: {}",
                            marker.contextPrefix, prefixFrameStack.peek());
                }
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

            // 用 effectiveMaxTokens 而非 maxTokens，为重叠预留空间
            if (totalTokens <= effectiveMaxTokens) {
                String sectionPrefix = buildContextPrefix(currentPrefix, section);
                String text = section.fullText();
                if (!text.isBlank()) {
                    String wrapped = wrapWithContext(text, sectionPrefix);
                    currentFrameChunks.add(new Chunk(
                            wrapped,
                            tokenEstimator.estimateTokenCountInText(wrapped),
                            sectionPrefix));
                }

            } else if (!section.children.isEmpty()) {
                String sectionPrefix2 = buildContextPrefix(currentPrefix, section);
                if (!section.title.isEmpty() || !section.content.isEmpty()) {
                    Section ownSection = new Section(
                            section.level, section.title, section.content);
                    List<Chunk> ownChunks = splitLargeSection(
                            ownSection, sectionPrefix2);
                    currentFrameChunks.addAll(ownChunks);
                }
                stack.push(new FrameMarker(sectionPrefix2));
                for (int i = section.children.size() - 1; i >= 0; i--) {
                    stack.push(section.children.get(i));
                }
                chunkFrameStack.push(new ArrayList<>());
                prefixFrameStack.push(sectionPrefix2);

            } else {
                String sectionPrefix3 = buildContextPrefix(currentPrefix, section);
                currentFrameChunks.addAll(
                        splitLargeSection(section, sectionPrefix3));
            }
        }

        return chunkFrameStack.isEmpty() ? List.of() : chunkFrameStack.pop();
    }

    private String wrapWithContext(String text, String contextPrefix) {
        if (contextPrefix == null || contextPrefix.isEmpty()) {
            return text;
        }
        return CONTEXT_PREFIX_MARKER + contextPrefix + "\n\n" + text;
    }

    /**
     * 从带上下文前缀的 chunk 文本中剥离前缀，返回纯正文。
     */
    private static String stripContextPrefix(String text) {
        if (text == null) return "";
        if (text.startsWith(CONTEXT_PREFIX_MARKER)) {
            int newlineIdx = text.indexOf('\n');
            if (newlineIdx >= 0) {
                int bodyStart = newlineIdx + 1;
                while (bodyStart < text.length() && text.charAt(bodyStart) == '\n') {
                    bodyStart++;
                }
                return text.substring(bodyStart);
            }
        }
        return text;
    }

    // ======================== 拆分大 Section ========================

    private List<Chunk> splitLargeSection(Section section, String contextPrefix) {
        String header = section.title.isEmpty() ? "" : section.title + "\n";
        String content = section.content;

        List<ContentBlock> blocks = splitIntoBlocks(content);

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder(header);
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);

        for (ContentBlock block : blocks) {
            int blockTokens = tokenEstimator.estimateTokenCountInText(block.text);

            if (blockTokens > effectiveMaxTokens && !block.isSplittable) {
                // 不可拆分超大块（如超大代码块）：单独输出，可能超过 maxTokens
                if (currentChunk.length() > header.length()) {
                    chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                            contextPrefix));
                }
                int headerTokens = tokenEstimator.estimateTokenCountInText(header);
                int totalChunkTokens = headerTokens + blockTokens;
                if (totalChunkTokens > maxTokens) {
                    log.warn("不可拆分块 token 数 ({}) 超过 maxTokens ({})",
                            totalChunkTokens, maxTokens);
                }
                chunks.add(createChunk(header + block.text, contextPrefix));
                currentChunk = new StringBuilder(header);
                currentTokens = headerTokens;

            } else if (blockTokens > effectiveMaxTokens && block.isSplittable) {
                // 可拆分超大块（如超长段落）：语义切分
                if (currentChunk.length() > header.length()) {
                    chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                            contextPrefix));
                }
                chunks.addAll(splitLongParagraph(header, block.text, contextPrefix));
                currentChunk = new StringBuilder(header);
                currentTokens = tokenEstimator.estimateTokenCountInText(header);

            } else if (currentTokens + blockTokens > effectiveMaxTokens) {
                // 加上当前块会超有效上限 → 先输出，再开新块
                chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                        contextPrefix));
                currentChunk = new StringBuilder(header);
                currentChunk.append(block.text).append("\n");
                currentTokens = tokenEstimator.estimateTokenCountInText(header)
                        + blockTokens;

            } else {
                currentChunk.append(block.text).append("\n");
                currentTokens += blockTokens;
            }
        }

        if (currentChunk.length() > header.length()) {
            chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                    contextPrefix));
        }
        return chunks;
    }

    /**
     * 创建 Chunk 对象。tokenCount 按最终文本（含上下文前缀）重新估算。
     */
    private Chunk createChunk(String rawText, String contextPrefix) {
        String finalText = wrapWithContext(rawText, contextPrefix);
        int realTokens = tokenEstimator.estimateTokenCountInText(finalText);
        return new Chunk(finalText, realTokens, contextPrefix);
    }

    private List<ContentBlock> splitIntoBlocks(String content) {
        List<ContentBlock> blocks = new ArrayList<>();
        List<BlockSpan> spans = new ArrayList<>();

        Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(content);
        while (codeMatcher.find()) {
            spans.add(new BlockSpan(codeMatcher.start(), codeMatcher.end(),
                    content.substring(codeMatcher.start(), codeMatcher.end()),
                    false));
        }

        Matcher tableMatcher = TABLE_PATTERN.matcher(content);
        while (tableMatcher.find()) {
            boolean overlaps = spans.stream().anyMatch(s ->
                    s.start < tableMatcher.end() && s.end > tableMatcher.start());
            if (!overlaps) {
                spans.add(new BlockSpan(tableMatcher.start(), tableMatcher.end(),
                        tableMatcher.group(0),
                        false));
            }
        }

        spans.sort(Comparator.comparingInt(a -> a.start));

        int cursor = 0;
        for (BlockSpan span : spans) {
            if (span.start > cursor) {
                String paragraphText = content.substring(cursor, span.start).strip();
                if (!paragraphText.isEmpty()) {
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

    private List<Chunk> splitLongParagraph(String header, String paragraph,
                                           String contextPrefix) {
        List<Chunk> chunks = new ArrayList<>();

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

        if (sentences.isEmpty()) {
            sentences.add(paragraph.strip());
        }
        if (sentences.isEmpty()) return chunks;

        try {
            if (embeddingModel != null) {
                return semanticChunk(header, sentences, contextPrefix);
            }
        } catch (Exception e) {
            log.warn("语义切分异常，降级为 token 切分。错误: {}", e.getMessage());
        }

        return splitLongParagraphByTokenFallback(header, sentences, contextPrefix);
    }

    private List<Chunk> semanticChunk(String header, List<String> sentences,
                                      String contextPrefix) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder(header);
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);
        List<String> currentSentences = new ArrayList<>();
        float[] accumulatedVector = null;

        for (String sentence : sentences) {
            int sentenceTokens = tokenEstimator.estimateTokenCountInText(sentence);

            if (currentSentences.isEmpty()) {
                currentSentences.add(sentence);
                currentChunk.append(sentence);
                currentTokens += sentenceTokens;
                accumulatedVector = embedSentence(sentence);
                if (accumulatedVector == null) {
                    throw new RuntimeException("无法获取句子向量");
                }
                continue;
            }

            boolean wouldExceedTokens = (currentTokens + sentenceTokens > effectiveMaxTokens);
            boolean isSemanticShift = false;

            if (!wouldExceedTokens) {
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
                    isSemanticShift = true;
                    log.warn("句子向量获取失败，强制切分");
                }
            }

            if (wouldExceedTokens || isSemanticShift) {
                if (currentChunk.length() > header.length()) {
                    chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                            contextPrefix));
                }
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
                float[] sentenceVec = embedSentence(sentence);
                if (sentenceVec != null) {
                    int count = currentSentences.size();
                    for (int i = 0; i < accumulatedVector.length; i++) {
                        accumulatedVector[i] =
                                (accumulatedVector[i] * count + sentenceVec[i])
                                        / (count + 1);
                    }
                }
                currentSentences.add(sentence);
                currentChunk.append(sentence);
                currentTokens += sentenceTokens;
            }
        }

        if (currentChunk.length() > header.length()) {
            chunks.add(createChunk(currentChunk.toString().stripTrailing(),
                    contextPrefix));
        }
        return chunks;
    }

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

    private List<Chunk> splitLongParagraphByTokenFallback(
            String header, List<String> sentences, String contextPrefix) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);

        for (String sentence : sentences) {
            if (sentence.isBlank()) continue;
            int sTokens = tokenEstimator.estimateTokenCountInText(sentence);

            if (currentTokens + sTokens > effectiveMaxTokens
                    && current.length() > header.length()) {
                chunks.add(createChunk(current.toString().stripTrailing(),
                        contextPrefix));
                current = new StringBuilder(header);
                currentTokens = tokenEstimator.estimateTokenCountInText(header);
            }
            current.append(sentence);
            currentTokens += sTokens;
        }

        if (current.length() > header.length()) {
            chunks.add(createChunk(current.toString().stripTrailing(),
                    contextPrefix));
        }
        return chunks;
    }

    // ======================== 合并小 Chunk ========================

    /**
     * <h3>合并低于 minTokens 的小片段</h3>
     *
     * <p><b>修复要点</b>：合并时剥离各自 chunk 的 {@code **上下文**: } 前缀，
     * 只保留最终一个前缀，避免多个前缀叠加导致 token 超限和内容污染。</p>
     *
     * <p>合并判定不再依赖两个 tokenCount 相加，而是<b>合并后实际重算</b>，
     * 避免 BPE 边界效应和前缀叠加造成的估算偏差。</p>
     */
    private List<Chunk> mergeSmallChunks(List<Chunk> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<Chunk> merged = new ArrayList<>();
        Chunk accumulator = null;

        for (Chunk chunk : chunks) {
            if (accumulator == null) {
                accumulator = chunk;
                continue;
            }

            // 基本条件：累加器过小 + 前缀兼容
            if (accumulator.tokenCount >= minTokens
                    || !isContextPrefixCompatible(accumulator.contextPrefix, chunk.contextPrefix)) {
                merged.add(accumulator);
                accumulator = chunk;
                continue;
            }

            // 剥离各自前缀，合并正文
            String combinedBody = accumulator.body() + "\n\n" + chunk.body();
            String mergedPrefix = chunk.contextPrefix.length() > accumulator.contextPrefix.length()
                    ? chunk.contextPrefix : accumulator.contextPrefix;
            String wrapped = wrapWithContext(combinedBody, mergedPrefix);
            int newTokens = tokenEstimator.estimateTokenCountInText(wrapped);

            // 合并后实际 token 超限 → 不合并
            if (newTokens > effectiveMaxTokens) {
                merged.add(accumulator);
                accumulator = chunk;
            } else {
                accumulator = new Chunk(wrapped, newTokens, mergedPrefix);
            }
        }

        if (accumulator != null) merged.add(accumulator);
        return merged;
    }

    private boolean isContextPrefixCompatible(String prefix1, String prefix2) {
        if (prefix1 == null || prefix2 == null) return false;
        if (prefix1.isEmpty() || prefix2.isEmpty()) return false;
        return prefix1.equals(prefix2)
                || prefix1.startsWith(prefix2)
                || prefix2.startsWith(prefix1);
    }

    // ======================== 重叠处理 ========================

    /**
     * <h3>在相邻 Chunk 之间添加重叠内容</h3>
     *
     * <p>由于切片阶段已使用 {@code effectiveMaxTokens = maxTokens - overlapTokens}，
     * 正常情况下每个 chunk 都留有约 {@code overlapTokens} 的空间，
     * {@code maxAllowedOverlap} 应约等于 overlapTokens。</p>
     */
    private List<Chunk> applyOverlap(List<Chunk> chunks) {
        if (chunks.size() <= 1 || overlapTokens <= 0) return chunks;

        List<Chunk> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            Chunk prev = chunks.get(i - 1);
            Chunk curr = chunks.get(i);

            int desiredOverlap = overlapTokens;
            int maxAllowedOverlap = maxTokens - curr.tokenCount;
            if (maxAllowedOverlap <= 0) {
                log.info("[overlap] chunk#{} 已满 (tokens={}, max={})，跳过重叠。" +
                                "若频繁出现，说明存在不可拆分超大块",
                        i, curr.tokenCount, maxTokens);
                result.add(curr);
                continue;
            }
            int actualTarget = Math.min(desiredOverlap, maxAllowedOverlap);
            if (actualTarget < desiredOverlap) {
                log.warn("[overlap] chunk#{} 空间不足，期望重叠={}, 实际={} (currTokens={}, max={})",
                        i, desiredOverlap, actualTarget, curr.tokenCount, maxTokens);
            }

            String prevBody = prev.body();
            String overlapText = extractTailByText(prevBody, actualTarget);

            if (overlapText.isEmpty() && actualTarget >= MIN_MEANINGFUL_OVERLAP) {
                log.warn("[overlap] chunk#{} 期望重叠={} tokens，但提取结果为空。" +
                        "prevBody 尾部可能全为空白或段落分隔符", i, actualTarget);
            }

            if (!overlapText.isEmpty()) {
                String newText = overlapText + "\n\n" + curr.text;
                int newTokens = tokenEstimator.estimateTokenCountInText(newText);
                log.debug("[overlap] chunk#{} 期望={}, 实际增加={} tokens",
                        i, actualTarget, newTokens - curr.tokenCount);
                result.add(new Chunk(newText, newTokens, curr.contextPrefix));
            } else {
                result.add(curr);
            }
        }
        return result;
    }

    /**
     * <h3>从文本尾部按段落提取指定 token 数量的内容</h3>
     */
    private String extractTailByText(String text, int targetTokens) {
        if (text == null || text.isEmpty() || targetTokens <= 0) return "";

        String[] paragraphs = text.split(
                "\\n\\s*\\n|\\n\\s*---\\s*\\n|\\n\\s*\\*\\*\\*\\s*\\n");

        StringBuilder tail = new StringBuilder();
        int tokens = 0;

        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String para = paragraphs[i].strip();
            if (para.isEmpty()) continue;

            int paraTokens = tokenEstimator.estimateTokenCountInText(para);

            // 单段本身超预算：硬截取尾部
            if (paraTokens > targetTokens) {
                int remain = targetTokens - tokens;
                if (remain <= 0) break;
                String truncated = TextTokenUtils.truncateTailByTokens(
                        para, remain, tokenEstimator);
                if (truncated.isEmpty()) {
                    // 截断后为空（全空白/分隔符），退化为按字符长度粗截
                    int fallbackLen = Math.min(para.length(), remain * 2);
                    String fallback = para.substring(para.length() - fallbackLen).strip();
                    if (!fallback.isEmpty()) {
                        truncated = fallback;
                    }
                }
                if (!truncated.isEmpty()) {
                    if (tail.length() == 0) {
                        tail.insert(0, truncated);
                    } else {
                        tail.insert(0, truncated + "\n\n");
                    }
                }
                break;
            }

            // 已有内容且再加会超过目标 → 停止
            if (tail.length() > 0 && tokens + paraTokens > targetTokens) break;

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

    private String buildContextPrefix(String currentPrefix, Section section) {
        if (section.title.isEmpty()) return currentPrefix;
        String titleText = section.title.replaceFirst("^#+\\s+", "").strip();
        return currentPrefix.isEmpty()
                ? titleText
                : currentPrefix + " > " + titleText;
    }

    // ======================== 内部数据结构 ========================

    record BlockSpan(int start, int end, String text, boolean isSplittable) {}

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
     */
    public static class Chunk {
        private final String text;
        private final int tokenCount;
        private final String contextPrefix;
        private final Map<String, String> metadata;

        public Chunk(String text, int tokenCount, String contextPrefix) {
            this(text, tokenCount, contextPrefix, new HashMap<>());
        }

        public Chunk(String text, int tokenCount, String contextPrefix, Map<String, String> metadata) {
            this.text = text;
            this.tokenCount = tokenCount;
            this.contextPrefix = contextPrefix;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }

        public String text() { return text; }
        public int tokenCount() { return tokenCount; }
        public String contextPrefix() { return contextPrefix; }
        public Map<String, String> metadata() { return metadata; }

        /**
         * 返回不含上下文前缀的正文。
         */
        public String body() {
            return stripContextPrefix(text);
        }

        @Override
        public String toString() {
            return "Chunk{tokens=" + tokenCount +
                    ", prefix='" + contextPrefix + "'" +
                    ", text='" + text.substring(0, Math.min(80, text.length()))
                    + "...'}";
        }
    }
}