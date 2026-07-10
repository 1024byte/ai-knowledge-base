package com.hai.aiknowledgebase.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
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
 * 实现 LangChain4j 的 {@link DocumentSplitter} 接口，可直接替换原有 splitter。
 * 支持语义动态切分（利用 Embedding 模型检测话题转折点）。
 */
@Slf4j
@Service
public class DocumentChunkerService implements DocumentSplitter {

    @Value("${document.chunk.min-tokens:400}")
    private int minTokens;

    @Value("${document.chunk.max-tokens:800}")
    private int maxTokens;

    @Value("${document.chunk.overlap-ratio:0.2}")
    private double overlapRatio;

    @Value("${document.chunk.semantic-threshold:0.75}")
    private double semanticThreshold;

    // 复用估算器实例（无状态，可共享）
    private final OpenAiTokenCountEstimator tokenEstimator =
            new OpenAiTokenCountEstimator(GPT_4_O_MINI);

    // Embedding 模型（本地运行，免费，无需 API Key）
    private final EmbeddingModel embeddingModel;

    public DocumentChunkerService() {
        // 初始化本地 Embedding 模型（约 30MB，CPU 可跑）
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        log.info("Embedding 模型初始化完成: AllMiniLmL6V2");
    }

    /**
     * 对 Markdown 文本进行切片，返回 chunk 列表。
     */
    public List<MarkdownDocumentChunker.Chunk> chunkMarkdown(String markdown) {
        MarkdownDocumentChunker chunker = createChunker();
        List<MarkdownDocumentChunker.Chunk> chunks = chunker.chunk(markdown);
        log.info("Markdown 切片完成: 输入 {} 字符 → {} 个 chunk", markdown.length(), chunks.size());
        return chunks;
    }

    /**
     * 对纯文本进行切片。
     */
    public List<MarkdownDocumentChunker.Chunk> chunkPlainText(String text) {
        MarkdownDocumentChunker chunker = createChunker();
        List<MarkdownDocumentChunker.Chunk> chunks = chunker.chunk(text);
        log.info("纯文本切片完成: 输入 {} 字符 → {} 个 chunk", text.length(), chunks.size());
        return chunks;
    }

    /**
     * 对任意文本自动判断格式并切片。
     */
    public List<MarkdownDocumentChunker.Chunk> chunk(String text) {
        if (isLikelyMarkdown(text)) {
            return chunkMarkdown(text);
        } else {
            return chunkPlainText(text);
        }
    }

    // ======================== LangChain4j DocumentSplitter 接口实现 ========================

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        Metadata metadata = document.metadata();
        List<MarkdownDocumentChunker.Chunk> chunks = chunk(text);

        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            MarkdownDocumentChunker.Chunk chunk = chunks.get(i);

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
        return new MarkdownDocumentChunker(
                minTokens,
                maxTokens,
                overlapRatio,
                tokenEstimator,
                embeddingModel,
                semanticThreshold
        );
    }

    /**
     * 简单启发式判断文本是否为 Markdown 格式。
     */
    private boolean isLikelyMarkdown(String text) {
        if (text == null || text.length() < 10) return false;

        String[] lines = text.split("\\n", 50);
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

        return headingCount >= 1 || tableCount >= 1;
    }
}