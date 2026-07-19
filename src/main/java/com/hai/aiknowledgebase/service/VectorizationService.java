package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hai.aiknowledgebase.common.CustomDocument;
import com.hai.aiknowledgebase.common.HashType;
import com.hai.aiknowledgebase.common.KeywordIndex;
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

/**
 * <h2>文档向量化服务</h2>
 *
 * <p>负责将上传的文档从原始文件处理为可检索的向量化数据，整个流程为：</p>
 *
 * <pre>
 * 原始文件（PDF/Word/Markdown等）
 *     │
 *     ├── 1. 文档解析     → DocumentParserService.parseDocument()
 *     │      将各类格式文件解析为纯文本 CustomDocument
 *     │
 *     ├── 2. 内容去重     → DocumentHashService.exists(contentHash)
 *     │      基于 SHA-256 内容哈希判断是否重复上传
 *     │
 *     ├── 3. 智能切片     → DocumentChunkerService.split()
 *     │      将长文本切分为语义完整的 TextSegment 列表
 *     │
 *     ├── 4. 向量化存储   → EmbeddingModel.embed() + EmbeddingStore.add()
 *     │      每个片段生成向量，存入 PGVector 向量库
 *     │
 *     ├── 5. BM25 索引    → KeywordIndex.index()
 *     │      每个片段同步加入 BM25 关键词倒排索引，支持混合检索
 *     │
 *     ├── 6. 状态更新     → 更新 DocumentMetadata.status = "active"
 *     │
 *     └── 7. 哈希持久化   → DocumentHashService.save()
 *           保存字节级哈希和文本级哈希，用于后续去重检测
 * </pre>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>异步执行</b>：{@code @Async} 注解使向量化在独立线程池中执行，
 *       不阻塞上传接口的 HTTP 响应。适用于大文档处理耗时场景。</li>
 *   <li><b>双重哈希去重</b>：字节级 SHA-256 和文本级 SHA-256 分别存储，
 *       字节级用于检测完全相同文件，文本级用于检测内容相同但格式不同的文件。</li>
 *   <li><b>双路径索引</b>：向量检索（PGVector）+ BM25 关键词检索（内存倒排索引），
 *       为后续混合检索提供数据基础。</li>
 *   <li><b>失败容错</b>：向量化失败时自动更新文档状态为 "failed" 并记录错误信息，
 *       不阻塞其他文档的处理。</li>
 * </ul>
 *
 * @see DocumentParserService 文档解析服务
 * @see DocumentChunkerService 文档切片服务
 * @see HybridSearchService 混合检索服务（提供 BM25 索引）
 * @see DocumentHashService 文档哈希去重服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorizationService {

    /** 文档解析服务：将 PDF/Word/Markdown 等格式解析为纯文本 */
    private final DocumentParserService documentParserService;

    /** 文档切片服务：将长文本切分为语义完整的 TextSegment 列表 */
    private final DocumentChunkerService documentChunkerService;

    /** LangChain4j 向量存储（PGVector），用于持久化向量数据 */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** 嵌入模型：将文本片段转为向量（ONNX 本地模型 paraphrase-multilingual-MiniLM-L12-v2） */
    private final EmbeddingModel embeddingModel;

    /** 文档哈希服务：用于内容去重检测 */
    private final DocumentHashService documentHashService;

    /** MyBatis-Plus 文档元数据 Mapper，用于更新文档状态 */
    private final DocumentMetadataMapper documentMetadataMapper;

    /** 混合检索服务：提供 BM25 关键词索引，向量化时同步建立关键词索引 */
    private final HybridSearchService hybridSearchService;

    // ======================== 异步向量化主流程 ========================

    /**
     * <h3>异步执行文档向量化流程</h3>
     *
     * <p>这是文档上传后的核心处理入口。通过 {@code @Async} 注解在独立线程中执行，
     * 避免阻塞上传接口的 HTTP 响应。适用场景：单个文档上传后的异步处理。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li><b>文档解析</b>：调用 {@link DocumentParserService#parseDocument} 解析文件为纯文本</li>
     *   <li><b>内容去重</b>：计算文本内容的 SHA-256 哈希，
     *       通过 {@link DocumentHashService#exists} 判重。重复则删除已上传文件并抛异常</li>
     *   <li><b>智能切片</b>：调用 {@link DocumentChunkerService#split} 将文本切分为片段列表</li>
     *   <li><b>向量化存储</b>：遍历每个片段，调用 {@link EmbeddingModel#embed} 生成向量，
     *       通过 {@link EmbeddingStore#add} 存入 PGVector 向量库</li>
     *   <li><b>BM25 索引</b>：遍历每个片段，调用 {@link KeywordIndex#index} 加入关键词倒排索引，
     *       为混合检索提供精确匹配能力</li>
     *   <li><b>状态更新</b>：更新 {@link DocumentMetadata#status} 为 "active"</li>
     *   <li><b>哈希持久化</b>：保存字节级哈希（检测完全相同的文件）和文本级哈希（检测内容相同但格式不同的文件）</li>
     * </ol>
     *
     * <h4>异常处理</h4>
     * <p>任何步骤失败都会捕获异常，调用 {@link #updateDocumentStatus} 将文档状态更新为 "failed"，
     * 并记录错误信息到数据库。这保证了单文档失败不影响其他文档的处理。</p>
     *
     * @param docId    文档在数据库中的唯一标识
     * @param filePath 上传文件的本地路径（处理完成后该文件可能被删除或保留）
     * @param category 文档分类标签（如 "技术文档"、"规章制度"）
     */
    @Async
    public void processVectorizationAsync(Long docId, Path filePath, String category) {
        try {
            // ===== 步骤1：文档解析 =====
            // 将 PDF/Word/Markdown 等格式文件解析为纯文本 CustomDocument
            String fileName = filePath.getFileName().toString();
            CustomDocument customDocument = documentParserService.parseDocument(
                    filePath, fileName);
            log.info("文档解析成功，内容长度: {}", customDocument.getContent().length());

            // ===== 步骤2：内容去重 =====
            // 计算文本内容的 SHA-256 哈希值
            String contentHash = DigestUtils.sha256Hex(customDocument.getContent());
            // 检查是否已存在相同内容的文档
            if (documentHashService.exists(contentHash)) {
                log.warn("检测到内容重复文档，文件名: {}", filePath.getFileName());
                // 删除已上传的服务器文件，避免磁盘空间浪费
                Files.deleteIfExists(filePath);
                throw new BusinessException(ResultCode.FILE_CONTENT_EXIST,
                        "该文档的正文内容已存在，无需重复上传");
            }

            // ===== 步骤3：构建 LangChain4j Document =====
            // 设置元数据：source（文件名）、category（分类）、document_id（数据库ID）
            DocumentMetadata docMeta = documentMetadataMapper.selectById(docId);
            String sourceFileName = (docMeta != null && docMeta.getFileName() != null)
                    ? docMeta.getFileName()
                    : filePath.getFileName().toString();
            Document document = Document.from(
                    customDocument.getContent(),
                    Metadata.from("source", sourceFileName)
                            .put("category", category)
                            .put("document_id", docId));

            log.info("待切片文本预览: \n{}", customDocument.getContent());

            // ===== 步骤4：智能切片 =====
            // 使用递归 Markdown 切片器将长文本切分为语义完整的片段
            List<TextSegment> segments = documentChunkerService.split(document);
            log.info("切片完成，生成 {} 个片段", segments.size());

            // ===== 步骤5：向量化 + 存储 =====
            // 遍历每个片段，生成向量并存入 PGVector
            for (TextSegment segment : segments) {
                // embeddingModel.embed(segment) 返回 Embedding 对象
                // .content() 提取其中的向量数据
                Embedding embedding = embeddingModel.embed(segment).content();
                // embeddingStore.add() 将向量和对应的文本片段一起存入 PGVector
                // PGVector 底层会写入 PostgreSQL 的 embeddings 表
                embeddingStore.add(embedding, segment);
            }

            // ===== 步骤6：BM25 关键词索引 =====
            // 从 HybridSearchService 获取 BM25 关键词倒排索引实例
            KeywordIndex keywordIndex = hybridSearchService.getKeywordIndex();
            // 将每个片段加入索引，chunkId 格式为 "docId_chunkIndex"
            // 例如 docId=42，第3个片段 → chunkId="42_2"
            for (int i = 0; i < segments.size(); i++) {
                String chunkId = docId + "_" + i;
                // keywordIndex.index() 内部会：
                // 1. 对文本进行中文分词
                // 2. 更新每个词在倒排索引中的文档频率
                // 3. 存储文档原文用于检索时返回
                keywordIndex.index(chunkId, segments.get(i).text());
            }
            log.info("BM25 关键词索引完成: docId={}, chunks={}",
                    docId, segments.size());

            // ===== 步骤7：更新文档状态为 "active" =====
            // 只有向量化和索引都成功完成，才将状态更新为 active
            LambdaUpdateWrapper<DocumentMetadata> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(DocumentMetadata::getId, docId)
                    .set(DocumentMetadata::getStatus, "active");
            documentMetadataMapper.update(null, wrapper);

            // ===== 步骤8：保存文件哈希（用于去重） =====
            // 8.1 字节级哈希：检测完全相同的文件（即使是相同内容的 PDF 和 Word 文件，字节级哈希也不同）
            try (InputStream is = Files.newInputStream(filePath)) {
                String byteHash = DigestUtils.sha256Hex(is);
                documentHashService.save(byteHash, HashType.HASH_TYPE_BYTE,
                        fileName, docId);
            } catch (IOException e) {
                throw new RuntimeException("计算文件哈希失败", e);
            }

            // 8.2 文本级哈希：检测内容相同但格式不同的文件
            // 例如：同一份文档的 PDF 版本和 Word 版本，文本级哈希相同
            String textHash = DigestUtils.sha256Hex(contentHash);
            documentHashService.save(textHash, HashType.HASH_TYPE_TEXT,
                    fileName, docId);

            log.info("向量化完成: docId={}", docId);

        } catch (Exception e) {
            // ===== 异常处理：更新状态为 "failed" =====
            log.error("异步向量化失败: docId={}", docId, e);
            // 更新文档状态为 failed，并记录错误信息（独立事务，不受外层事务影响）
            updateDocumentStatus(docId, "failed", e.getMessage());
        }
    }

    // ======================== 状态更新 ========================

    /**
     * <h3>更新文档状态（独立事务）</h3>
     *
     * <p>使用 {@code @Transactional} 确保状态更新的原子性。
     * 独立事务意味着即使外层异步方法的事务回滚，此方法也能独立提交。</p>
     *
     * <h4>使用场景</h4>
     * <ul>
     *   <li>向量化成功时：更新 status 为 "active"</li>
     *   <li>向量化失败时：更新 status 为 "failed"，同时记录错误信息</li>
     * </ul>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>LambdaUpdateWrapper</b>：MyBatis-Plus 的条件构造器，
     *       提供类型安全的 SQL 更新语句构建。通过方法引用 {@code DocumentMetadata::getId}
     *       代替字符串列名，编译期即可检测字段名错误。</li>
     *   <li><b>{@code eq(DocumentMetadata::getId, docId)}</b>：
     *       生成 SQL 条件 {@code WHERE id = docId}</li>
     *   <li><b>{@code set(DocumentMetadata::getStatus, status)}</b>：
     *       生成 SQL 赋值 {@code SET status = 'active'/'failed'}</li>
     *   <li><b>{@code documentMetadataMapper.update(null, wrapper)}</b>：
     *       执行 UPDATE 语句。第一个参数为 null 表示不传实体对象，仅使用 Wrapper 条件。</li>
     * </ul>
     *
     * @param docId        文档在数据库中的唯一标识
     * @param status       目标状态：通常为 "active" 或 "failed"
     * @param errorMessage 错误信息（成功时为 null）
     */
    @Transactional
    public void updateDocumentStatus(Long docId, String status, String errorMessage) {
        LambdaUpdateWrapper<DocumentMetadata> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(DocumentMetadata::getId, docId)
                .set(DocumentMetadata::getStatus, status);
        // 仅当有错误信息时才更新 error_message 字段
        if (errorMessage != null) {
            wrapper.set(DocumentMetadata::getErrorMessage, errorMessage);
        }
        documentMetadataMapper.update(null, wrapper);
        log.info("文档状态已更新: docId={}, status={}", docId, status);
    }
}