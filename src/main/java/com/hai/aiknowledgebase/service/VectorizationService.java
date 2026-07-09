package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hai.aiknowledgebase.entity.DocumentMetadata;
import com.hai.aiknowledgebase.mapper.DocumentMetadataMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.hai.aiknowledgebase.common.FileUtils.getFileExtension;
import static com.hai.aiknowledgebase.common.FileUtils.loadDocumentContent;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorizationService {

    private final DocumentParserService documentParserService;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    @Value("${document.chunk-size:500}")
    private int chunkSize;

    @Value("${document.chunk-overlap:50}")
    private int chunkOverlap;

    private final DocumentMetadataMapper documentMetadataMapper;


    @Async
    public void processVectorizationAsync(Long docId, Path filePath, String category) {
        try {
            // 1. 加载内容、切分、向量化（复用现有逻辑）
            String parsedContent = null;
            String fileName = filePath.getFileName().toString();
            String extension = getFileExtension(fileName).toLowerCase();
            // 判断文件类型：如果是纯文本，直接读取；否则调用 MinerU
            Set<String> plainTextExtensions = Set.of("md", "txt");
            if (plainTextExtensions.contains(extension)) {
                // 纯文本，使用原有方法
                parsedContent = loadDocumentContent(filePath.toFile(), fileName);
                log.info("使用基础文本解析，内容长度: {}", parsedContent.length());
            } else {
                // 复杂文档（PDF、DOCX、PPTX、XLSX、图片等），调用 MinerU
                try {
                    parsedContent = documentParserService.parsePdf(filePath); // 方法名可保留
                    log.info("MinerU 解析成功，内容长度: {}", parsedContent.length());
                } catch (Exception e) {
                    log.error("MinerU 解析失败，回退到基础文本解析", e);
                    // 回退：尝试用基础方式读取（可能效果差）
                    parsedContent = loadDocumentContent(filePath.toFile(), fileName);
                }
            }
            Document document = Document.from(parsedContent, Metadata.from("source", filePath.getFileName().toString())
                    .put("category", category));
            DocumentSplitter splitter = new DocumentByParagraphSplitter(chunkSize, chunkOverlap);
            List<TextSegment> segments = splitter.split(document);
            for (TextSegment segment : segments) {
                Embedding embedding = embeddingModel.embed(segment).content();
                embeddingStore.add(embedding, segment);
            }
            // 2. 更新状态为 "active"
            LambdaUpdateWrapper<DocumentMetadata> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(DocumentMetadata::getId, docId)
                    .set(DocumentMetadata::getStatus, "active");
            documentMetadataMapper.update(null, wrapper);
            log.info("向量化完成: docId={}", docId);
        } catch (Exception e) {
            // 更新状态为 "failed"
            log.error("异步向量化失败: docId={}", docId, e);
            //向量化失败，更新状态为 failed，记录错误信息（独立事务）
            updateDocumentStatus(docId, "failed", e.getMessage());
        }
    }

    /**
     * 更新文档状态（独立事务）
     * 用于异步向量化完成或失败时更新状态
     */
    @Transactional
    public void updateDocumentStatus(Long docId, String status, String errorMessage) {
        LambdaUpdateWrapper<DocumentMetadata> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(DocumentMetadata::getId, docId)
                .set(DocumentMetadata::getStatus, status);
        if (errorMessage != null) {
            wrapper.set(DocumentMetadata::getErrorMessage, errorMessage);
        }
        documentMetadataMapper.update(null, wrapper);
        log.info("文档状态已更新: docId={}, status={}", docId, status);
    }
}
