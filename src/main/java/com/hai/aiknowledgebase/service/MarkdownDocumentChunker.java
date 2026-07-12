package com.hai.aiknowledgebase.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

import java.text.BreakIterator;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 Markdown 标题层级、段落边界和语义相似度的智能文档切片器。
 * <p>
 * 核心规则：
 * <ul>
 *   <li>顺着标题层级和段落边界切</li>
 *   <li>标题跟着它管理的内容走</li>
 *   <li>表格尽量整块保留成 Markdown</li>
 *   <li>不拆散内嵌对象（代码块、表格等）</li>
 *   <li>400–800 token 一块，留 20% 重叠</li>
 *   <li>上下文不断裂，语义被完整保留</li>
 *   <li>支持基于 Embedding 模型的语义动态切分（在话题转折处自动切开）</li>
 *   <li>迭代切片（使用显式栈避免递归深度问题）</li>
 * </ul>
 */
@Slf4j
public class MarkdownDocumentChunker {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(^\\|.+)\\n(\\|?[-:| ]+)\\n((?:\\|.+\\n?)+)",
            Pattern.MULTILINE
    );
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
    /** Token 计算器（LangChain4j 官方接口） */
    private final TokenCountEstimator tokenEstimator;
    /** Embedding 模型（用于语义切分，可为 null） */
    private final EmbeddingModel embeddingModel;
    /** 语义转折阈值（0.7-0.8 推荐） */
    private final double semanticThreshold;

    /**
     * 完整构造函数（支持语义切分）
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

        // 2. 迭代切片
        List<Chunk> rawChunks = iterativeChunk(sections, "");

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
            int contentEnd = (i + 1 < headings.size()) ? headings.get(i + 1).start : markdown.length();
            String content = markdown.substring(contentStart, contentEnd).strip();
            sections.add(new Section(heading.level, heading.fullLine, content));
        }

        return buildSectionTree(sections);
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

    // ======================== 迭代切片 ========================

    /**
     * 迭代切片核心逻辑（使用显式栈避免深层嵌套导致的栈溢出）
     */
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
                    log.warn("帧上下文不匹配，预期: {}, 实际: {}", marker.contextPrefix, prefixFrameStack.peek());
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

            if (totalTokens <= maxTokens) {
                String text = section.fullText();
                if (!text.isBlank()) {
                    currentFrameChunks.add(new Chunk(text, tokenEstimator.estimateTokenCountInText(text), currentPrefix));
                }
            } else if (!section.children.isEmpty()) {
                String childPrefix = buildContextPrefix(currentPrefix, section);
                stack.push(new FrameMarker(childPrefix));
                for (int i = section.children.size() - 1; i >= 0; i--) {
                    stack.push(section.children.get(i));
                }
                chunkFrameStack.push(new ArrayList<>());
                prefixFrameStack.push(childPrefix);
            } else {
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

        List<ContentBlock> blocks = splitIntoBlocks(content);

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder(header);
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);

        for (ContentBlock block : blocks) {
            int blockTokens = tokenEstimator.estimateTokenCountInText(block.text);

            if (blockTokens > maxTokens && !block.isSplittable) {
                if (currentChunk.length() > header.length()) {
                    chunks.add(new Chunk(currentChunk.toString().stripTrailing(), currentTokens, contextPrefix));
                }
                int headerTokens = tokenEstimator.estimateTokenCountInText(header);
                int totalChunkTokens = headerTokens + blockTokens;
                if (totalChunkTokens > maxTokens) {
                    log.warn("不可拆分块 token 数 ({}) 超过 maxTokens ({})，可能导致后续向量化或检索异常",
                            totalChunkTokens, maxTokens);
                }
                chunks.add(new Chunk(header + block.text, totalChunkTokens, contextPrefix));
                currentChunk = new StringBuilder(header);
                currentTokens = headerTokens;
            } else if (blockTokens > maxTokens && block.isSplittable) {
                if (currentChunk.length() > header.length()) {
                    chunks.add(new Chunk(currentChunk.toString().stripTrailing(), currentTokens, contextPrefix));
                }
                chunks.addAll(splitLongParagraph(header, block.text, contextPrefix));
                currentChunk = new StringBuilder(header);
                currentTokens = tokenEstimator.estimateTokenCountInText(header);
            } else if (currentTokens + blockTokens > maxTokens) {
                chunks.add(new Chunk(currentChunk.toString().stripTrailing(), currentTokens, contextPrefix));
                currentChunk = new StringBuilder(header);
                currentChunk.append(block.text).append("\n");
                currentTokens = tokenEstimator.estimateTokenCountInText(header) + blockTokens;
            } else {
                currentChunk.append(block.text).append("\n");
                currentTokens += blockTokens;
            }
        }

        if (currentChunk.length() > header.length()) {
            chunks.add(new Chunk(currentChunk.toString().stripTrailing(), currentTokens, contextPrefix));
        }

        return chunks;
    }

    /**
     * 将内容拆分为不可拆分的块（表格、代码块）和普通段落。
     */
    private List<ContentBlock> splitIntoBlocks(String content) {
        List<ContentBlock> blocks = new ArrayList<>();
        List<BlockSpan> spans = new ArrayList<>();

        Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(content);
        while (codeMatcher.find()) {
            spans.add(new BlockSpan(codeMatcher.start(), codeMatcher.end(),
                    content.substring(codeMatcher.start(), codeMatcher.end()), false));
        }

        Matcher tableMatcher = TABLE_PATTERN.matcher(content);
        while (tableMatcher.find()) {
            boolean overlaps = spans.stream().anyMatch(s ->
                    s.start < tableMatcher.end() && s.end > tableMatcher.start());
            if (!overlaps) {
                spans.add(new BlockSpan(tableMatcher.start(), tableMatcher.end(),
                        tableMatcher.group(0), false));
            }
        }

        spans.sort((a, b) -> Integer.compare(a.start, b.start));

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

    // ======================== 语义动态切分核心 ========================

    /**
     * 对长段落按句子边界切分，利用 Embedding 模型检测语义转折点。
     * 当相邻句子语义相似度低于阈值时，强制切分，确保每个 chunk 主题聚焦。
     * <p>
     * 性能优化：使用增量向量，避免每次重新计算整个累积块的 Embedding。
     */
    private List<Chunk> splitLongParagraph(String header, String paragraph, String contextPrefix) {
        List<Chunk> chunks = new ArrayList<>();

        // 1. 用 BreakIterator 提取句子列表
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
        iterator.setText(paragraph);
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        int end = iterator.next();
        while (end != BreakIterator.DONE) {
            String sentence = paragraph.substring(start, end).strip();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
            start = end;
            end = iterator.next();
        }

        if (sentences.isEmpty()) return chunks;

        // 2. 如果没有 EmbeddingModel，回退到纯 Token 数切分
        if (embeddingModel == null) {
            return splitLongParagraphByTokenFallback(header, sentences, contextPrefix);
        }

        // 3. 语义切分核心逻辑（增量向量优化版）
        StringBuilder currentChunk = new StringBuilder(header);
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);
        List<String> currentSentences = new ArrayList<>();

        // 累积向量（当前块的向量表示）
        float[] accumulatedVector = null;

        for (String sentence : sentences) {
            int sentenceTokens = tokenEstimator.estimateTokenCountInText(sentence);

            // 如果当前块为空，直接加入
            if (currentSentences.isEmpty()) {
                currentSentences.add(sentence);
                currentChunk.append(sentence);
                currentTokens += sentenceTokens;
                // 初始化累积向量
                accumulatedVector = embeddingModel.embed(sentence).content().vector().clone();
                continue;
            }

            // 检查加入新句子后是否超 Token 上限
            boolean wouldExceedTokens = (currentTokens + sentenceTokens > maxTokens);

            // 检查语义是否转折（用累积向量 vs 新句子向量）
            boolean isSemanticShift = false;
            if (!wouldExceedTokens) {
                // 计算新句子的向量
                float[] sentenceVec = embeddingModel.embed(sentence).content().vector();
                double similarity = cosineSimilarity(accumulatedVector, sentenceVec);

                if (similarity < semanticThreshold) {
                    isSemanticShift = true;
                    log.debug("语义转折检测: 相似度 {} < 阈值 {}, 在句子处切分: '{}'",
                            String.format("%.3f", similarity), semanticThreshold,
                            sentence.substring(0, Math.min(30, sentence.length())));
                }
            }

            // 如果超 Token 或语义转折，则切分
            if (wouldExceedTokens || isSemanticShift) {
                // 保存当前块
                if (currentChunk.length() > header.length()) {
                    chunks.add(new Chunk(currentChunk.toString().stripTrailing(),
                            currentTokens, contextPrefix));
                }
                // 重置新块（包含当前句子）
                currentChunk = new StringBuilder(header);
                currentSentences.clear();
                currentChunk.append(sentence);
                currentTokens = tokenEstimator.estimateTokenCountInText(header) + sentenceTokens;
                currentSentences.add(sentence);
                // 重置累积向量为当前句子的向量
                accumulatedVector = embeddingModel.embed(sentence).content().vector().clone();
            } else {
                // 继续累积：更新累积向量（加权平均）
                float[] sentenceVec = embeddingModel.embed(sentence).content().vector();
                int count = currentSentences.size();
                for (int i = 0; i < accumulatedVector.length; i++) {
                    // 计算增量平均值：new_avg = (old_avg * n + new_val) / (n + 1)
                    accumulatedVector[i] = (accumulatedVector[i] * count + sentenceVec[i]) / (count + 1);
                }
                currentSentences.add(sentence);
                currentChunk.append(sentence);
                currentTokens += sentenceTokens;
            }
        }

        // 处理最后一个块
        if (currentChunk.length() > header.length()) {
            chunks.add(new Chunk(currentChunk.toString().stripTrailing(),
                    currentTokens, contextPrefix));
        }

        return chunks;
    }

    /**
     * 计算两个向量的余弦相似度
     */
    private double cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length == 0) return 0.0;
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        if (norm1 == 0 || norm2 == 0) return 0.0;
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * Fallback：纯 Token 数切分（无语义检测）
     * 使用已经切好的句子列表，按 Token 累积切分。
     */
    private List<Chunk> splitLongParagraphByTokenFallback(String header, List<String> sentences,
                                                          String contextPrefix) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        int currentTokens = tokenEstimator.estimateTokenCountInText(header);

        for (String sentence : sentences) {
            int sTokens = tokenEstimator.estimateTokenCountInText(sentence);
            if (currentTokens + sTokens > maxTokens && current.length() > header.length()) {
                chunks.add(new Chunk(current.toString().stripTrailing(), currentTokens, contextPrefix));
                current = new StringBuilder(header);
                currentTokens = tokenEstimator.estimateTokenCountInText(header);
            }
            current.append(sentence);
            currentTokens += sTokens;
        }

        if (current.length() > header.length()) {
            chunks.add(new Chunk(current.toString().stripTrailing(), currentTokens, contextPrefix));
        }
        return chunks;
    }

    // ======================== 合并小 chunk ========================

    /**
     * 合并过小的 chunk：如果相邻 chunk 合并后不超过 maxTokens，且属于同一上下文前缀，则合并。
     */
    private List<Chunk> mergeSmallChunks(List<Chunk> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<Chunk> merged = new ArrayList<>();
        Chunk accumulator = null;

        for (Chunk chunk : chunks) {
            if (accumulator == null) {
                accumulator = chunk;
            } else if (accumulator.tokenCount < minTokens
                    && accumulator.tokenCount + chunk.tokenCount <= maxTokens
                    && accumulator.contextPrefix.equals(chunk.contextPrefix)) {
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
     * 为相邻 chunk 添加重叠内容，确保追加后不超过 maxTokens。
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
            int maxAllowedOverlap = maxTokens - curr.tokenCount;
            if (maxAllowedOverlap <= 0) {
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
     * 从文本末尾提取约 targetTokens 个 token 的文本，按段落边界截取。
     */
    private String extractTailByText(String text, int targetTokens) {
        if (targetTokens <= 0) return "";

        String[] paragraphs = text.split("\\n\\s*\\n|\\n\\s*---\\s*\\n|\\n\\s*\\*\\*\\*\\s*\\n");
        StringBuilder tail = new StringBuilder();
        int tokens = 0;

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

    private String buildContextPrefix(String currentPrefix, Section section) {
        if (section.title.isEmpty()) return currentPrefix;
        String titleText = section.title.replaceFirst("^#+\\s+", "").strip();
        return currentPrefix.isEmpty() ? titleText : currentPrefix + " > " + titleText;
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

    // ======================== Chunk 结果 ========================

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
                    ", text='" + text.substring(0, Math.min(80, text.length())) + "...'}";
        }
    }
}