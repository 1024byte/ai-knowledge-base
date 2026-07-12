package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hai.aiknowledgebase.common.HashType;
import com.hai.aiknowledgebase.common.ResultCode;
import com.hai.aiknowledgebase.entity.DocumentMetadata;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.DocumentMetadataMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorizationService {

    private final DocumentParserService documentParserService;
    private final DocumentChunkerService documentChunkerService;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentHashService documentHashService;

    private final DocumentMetadataMapper documentMetadataMapper;


    @Async
    public void processVectorizationAsync(Long docId, Path filePath, String category) {
        try {
            // 1. 加载内容、切分、向量化（复用现有逻辑）
            String fileName = filePath.getFileName().toString();
            String parsedContent = documentParserService.parseDocument(filePath, fileName);
            log.info("文档解析成功，内容长度: {}", parsedContent.length());
            log.info("内容: {}", parsedContent);
            String contentHash = DigestUtils.sha256Hex(parsedContent);
            // 1.1查重判断
            if (documentHashService.exists(contentHash)) {
                log.warn("检测到内容重复文档，文件名: {}", filePath.getFileName());
                Files.deleteIfExists(filePath);//删除已上传的服务器文件
                throw new BusinessException(ResultCode.FILE_CONTENT_EXIST,"该文档的正文内容已存在，无需重复上传");
            }

            // 2. 构建 Document
            Document document = Document.from(parsedContent, Metadata.from("source", filePath.getFileName().toString())
                    .put("category", category).put("document_id",docId));

            // 3. 切分 + 向量化 + 存储（使用递归 Markdown 切片器）
            List<TextSegment> segments = documentChunkerService.split(document);
            for (TextSegment segment : segments) {
                Embedding embedding = embeddingModel.embed(segment).content();
                embeddingStore.add(embedding, segment);
            }

            // 4. 更新状态为 "active"
            LambdaUpdateWrapper<DocumentMetadata> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(DocumentMetadata::getId, docId)
                    .set(DocumentMetadata::getStatus, "active");
            documentMetadataMapper.update(null, wrapper);
            // 5. 保存文件哈希（可选，用于去重）String byteHash;
            try (InputStream is = Files.newInputStream(filePath)) {
                String byteHash  = DigestUtils.sha256Hex(is);
                documentHashService.save(byteHash, HashType.HASH_TYPE_BYTE,fileName,docId);
            } catch (IOException e) {
                throw new RuntimeException("计算文件哈希失败", e);
            }
            //保存文件内容hash
            String textHash  = DigestUtils.sha256Hex(contentHash);
            documentHashService.save(textHash, HashType.HASH_TYPE_TEXT, fileName,docId);

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
