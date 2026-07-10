package com.hai.aiknowledgebase.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.model.openai.OpenAiChatModelName.GPT_4_O_MINI;

/**
 * 基于 Markdown 标题层级和段落边界的递归文档切片服务。
 * <p>
 * 替代 LangChain4j 内置的 DocumentByParagraphSplitter，提供更智能的切片策略：
 * <ul>
 *   <li>顺着标题层级和段落边界切</li>
 *   <li>标题跟着它管理的内容走</li>
 *   <li>表格尽量整块保留成 Markdown</li>
 *   <li>不拆散内嵌对象（代码块、表格等）</li>
 *   <li>400–8008 token 一块，留 20% 重叠</li>
 *   <li>递归切片</li>
 * </ul>
 * <p>
 * 同时实现 LangChain4j 的 {@link DocumentSplitter} 接口，可直接替换原有 splitter。
 */
@Slf4j
@Service
public class DocumentChunkerService implements DocumentSplitter {

    @Value("${document.chunk.min-tokens:400}")
    private int minTokens;

    @Value("${document.chunk.max-tokens:8008}")
    private int maxTokens;

    @Value("${document.chunk.overlap-ratio:0.2}")
    private double overlapRatio;

    /**
     * 对 Markdown 文本进行递归切片，返回 chunk 列表。
     *
     * @param markdown 原始 Markdown 文本
     * @return 切片结果
     */
    public List<MarkdownDocumentChunker.Chunk> chunkMarkdown(String markdown) {
        MarkdownDocumentChunker chunker = createChunker();
        List<MarkdownDocumentChunker.Chunk> chunks = chunker.chunk(markdown);
        log.info("Markdown 切片完成: 输入 {} 字符 → {} 个 chunk", markdown.length(), chunks.size());
        return chunks;
    }

    /**
     * 对纯文本（非 Markdown）进行切片。
     * 按段落边界切分，保留重叠。
     *
     * @param text 原始文本
     * @return 切片结果
     */
    public List<MarkdownDocumentChunker.Chunk> chunkPlainText(String text) {
        MarkdownDocumentChunker chunker = createChunker();
        // 纯文本也走 Markdown 解析器，无标题时会整体作为一个 section 处理
        List<MarkdownDocumentChunker.Chunk> chunks = chunker.chunk(text);
        log.info("纯文本切片完成: 输入 {} 字符 → {} 个 chunk", text.length(), chunks.size());
        return chunks;
    }

    /**
     * 对任意文本自动判断格式并切片。
     * 如果文本包含 Markdown 标题标记（# 开头行），按 Markdown 模式切片；否则按纯文本模式。
     *
     * @param text 原始文本
     * @return 切片结果
     */
    public List<MarkdownDocumentChunker.Chunk> chunk(String text) {
        if (isLikelyMarkdown(text)) {
            return chunkMarkdown(text);
        } else {
            return chunkPlainText(text);
        }
    }

    // ======================== LangChain4j DocumentSplitter 接口实现 ========================

    /**
     * 实现 LangChain4j DocumentSplitter 接口，可直接替换 DocumentByParagraphSplitter。
     * <p>
     * 将 Document 切分为 TextSegment 列表，保留原始 Metadata 并注入上下文前缀。
     */
    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        Metadata metadata = document.metadata();

        List<MarkdownDocumentChunker.Chunk> chunks = chunk(text);

        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            MarkdownDocumentChunker.Chunk chunk = chunks.get(i);

            // 构建增强 Metadata：保留原始 + 添加 chunk 序号和上下文前缀
            Metadata chunkMetadata = Metadata.from(metadata.toMap());
            chunkMetadata.put("chunk_index", i);
            chunkMetadata.put("chunk_total", chunks.size());
            if (!chunk.contextPrefix().isEmpty()) {
                chunkMetadata.put("context_prefix", chunk.contextPrefix());
            }

            segments.add(TextSegment.from(chunk.text(), chunkMetadata));
        }

        log.debug("Document 切片完成: {} 字符 → {} 个 TextSegment", text.length(), segments.size());
        return segments;
    }

    // ======================== 内部方法 ========================

    private MarkdownDocumentChunker createChunker() {
        OpenAiTokenCountEstimator estimator = new OpenAiTokenCountEstimator(GPT_4_O_MINI);
        return new MarkdownDocumentChunker(minTokens, maxTokens, overlapRatio,estimator);
    }

    /**
     * 简单启发式判断文本是否为 Markdown 格式。
     * 检测标题标记（# 开头行）或表格标记（| 开头行）。
     */
    private boolean isLikelyMarkdown(String text) {
        if (text == null || text.length() < 10) return false;

        String[] lines = text.split("\\n", 50);  // 只检查前 50 行
        int headingCount = 0;
        int tableCount = 0;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.matches("^#{1,6}\\s+.+")) {
                headingCount++;
            }
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                tableCount++;
            }
        }

        return headingCount >= 1 || tableCount >= 2;
    }
}
