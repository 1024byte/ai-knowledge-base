package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.common.CustomDocument;
import com.hai.aiknowledgebase.common.FileUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @Value("${document.chunk.semantic-threshold:0.65}")
    private double semanticThreshold;

    // 复用估算器实例（无状态，可共享）
    private final TokenCountEstimator  tokenCountEstimator;

    // Embedding 模型（本地运行，免费，无需 API Key）
    private final EmbeddingModel embeddingModel;

    private final DocumentRouter documentRouter;

    // 构造器注入（Spring 4.3+ 自动注入，无需 @Autowired）
    public DocumentChunkerService(TokenCountEstimator tokenEstimator,
                                  EmbeddingModel embeddingModel,
                                  DocumentRouter documentRouter) {
        this.tokenCountEstimator = tokenEstimator;
        this.embeddingModel = embeddingModel;
        this.documentRouter = documentRouter;
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
        String fileName = document.metadata().getString("source");
        String extension = FileUtils.getFileExtension(fileName);

        Metadata metadata = document.metadata();
        Map <String, Object> metadataMap = metadata.toMap();
        CustomDocument customDocument =  CustomDocument.builder()
                .fileName(metadataMap.get("source").toString())
                .content(document.text())
                .format(CustomDocument.Format.fromString(extension)).build();

        List<MarkdownDocumentChunker.Chunk> chunks = documentRouter.route(customDocument);

        String text = document.text();

//        List<MarkdownDocumentChunker.Chunk> chunks = chunk(text);

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
                tokenCountEstimator ,
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