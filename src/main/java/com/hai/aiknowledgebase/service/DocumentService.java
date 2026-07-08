package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hai.aiknowledgebase.common.ResultCode;
import com.hai.aiknowledgebase.dto.DocumentFileDTO;
import com.hai.aiknowledgebase.dto.DocumentInfo;
import com.hai.aiknowledgebase.entity.Category;
import com.hai.aiknowledgebase.entity.DocumentMetadata;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.CategoryMapper;
import com.hai.aiknowledgebase.mapper.DocumentMetadataMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate; // 仅用于操作 embeddings 表
    private final DocumentMetadataMapper documentMetadataMapper;
    private final CategoryMapper categoryMapper;

    @Value("${document.upload-path}")
    private String uploadPath;

    @Value("${document.chunk-size:500}")
    private int chunkSize;

    @Value("${document.chunk-overlap:50}")
    private int chunkOverlap;

    @Value("${vectorstore.pgvector.table-name:embeddings}")
    private String tableName;

    @Value("${file.upload.root:./uploads}")
    private String uploadRootPath;

    public DocumentService(EmbeddingStore<TextSegment> embeddingStore,
                           EmbeddingModel embeddingModel,
                           JdbcTemplate jdbcTemplate,
                           DocumentMetadataMapper documentMetadataMapper,
                           CategoryMapper categoryMapper) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        this.documentMetadataMapper = documentMetadataMapper;
        this.categoryMapper = categoryMapper;
    }

    /**
     * 上传文档并按分类存储
     */
    @Transactional
    public Long uploadDocument(MultipartFile file, String category) {
        try {
            if (file == null || file.isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "文件不能为空");
            }
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "文件名不能为空");
            }
            log.info("开始处理文档: {}, 分类: {}", originalFilename, category);

            // 1. 处理分类名称
            String normalizedCategory = (category == null || category.trim().isEmpty())
                    ? "未分类"
                    : category.trim();
            // 2. 生成安全的文件名
            String safeFilename = sanitizeFilename(originalFilename);
            String uniqueFilename = generateUniqueFilename(safeFilename);
            String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            Path uploadDir = Paths.get(uploadRootPath, normalizedCategory, dateDir);
            // 3. 创建目录
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
                log.info("创建上传目录: {}", uploadDir);
            }
            // 4. 保存文件
            Path filePath = uploadDir.resolve(uniqueFilename);
            try {
                Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
                log.info("文件已保存到: {}", filePath);
            } catch (IOException e) {
                log.error("文件复制失败: {}", e.getMessage(), e);
                throw new BusinessException(ResultCode.FILE_SAVE_ERROR, e.getMessage());
            }
            // 5. 保存元数据到数据库
            DocumentMetadata metadata = new DocumentMetadata();
            metadata.setFileName(originalFilename);
            metadata.setFilePath(filePath.toString());
            metadata.setCategory(normalizedCategory);
            metadata.setFileSize(file.getSize());
            metadata.setFileType(getFileExtension(originalFilename));
            metadata.setStatus("active");
            documentMetadataMapper.insert(metadata);
            Long docId = metadata.getId();
            log.info("文档记录已创建: id={}, filePath={}", docId, filePath);
            // 6. 确保分类存在
            ensureCategoryExists(normalizedCategory);
            // 7. 注册事务同步回调：只有主事务成功提交后，才触发异步向量化
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            log.info("主事务已提交，触发异步向量化: docId={}", docId);
                            processVectorizationAsync(docId, filePath, normalizedCategory);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            // 可选：如果事务回滚，记录日志
                            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                                log.warn("主事务回滚，不触发异步向量化: docId={}", docId);
                                // 需要清理已保存的文件吗？这里可以视情况决定
                            }
                        }
                    }
            );
            return metadata.getId();
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件上传失败");
        }
    }

    @Async
    public void processVectorizationAsync(Long docId, Path filePath, String category) {
        try {
            // 1. 加载内容、切分、向量化（复用现有逻辑）
            String content = loadDocumentContent(filePath.toFile(), filePath.getFileName().toString());
            Document document = Document.from(content, Metadata.from("source", filePath.getFileName().toString())
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

    /**
     * 生成唯一文件名
     */
    private String generateUniqueFilename(String originalFilename) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return uuid + "_" + originalFilename;
    }

    /**
     * 确保分类在 categories 表中存在
     */
    private void ensureCategoryExists(String categoryName) {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getName, categoryName);
        if (categoryMapper.selectCount(wrapper) == 0) {
            Category category = new Category();
            category.setName(categoryName);
            categoryMapper.insert(category);
            log.info("自动创建分类记录: {}", categoryName);
        }
    }

    /**
     * 查询所有上传的历史文档列表
     */
    public List<DocumentInfo> listDocuments() {
        log.info("查询历史文档列表");

        LambdaQueryWrapper<DocumentMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentMetadata::getStatus, "active")
                .orderByDesc(DocumentMetadata::getUploadTime);

        List<DocumentMetadata> list = documentMetadataMapper.selectList(wrapper);

        if (list.isEmpty()) {
            log.info("暂无文档");
            return Collections.emptyList();
        }

        Map<String, Integer> chunkCountMap = getChunkCountMap();

        return list.stream().map(metadata -> {
            int chunkCount = chunkCountMap.getOrDefault(metadata.getFileName(), 0);
            return new DocumentInfo(
                    metadata.getFileName(),
                    metadata.getFileSize(),
                    metadata.getFileType(),
                    metadata.getUploadTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    chunkCount
            );
        }).collect(Collectors.toList());
    }

    /**
     * 从PGVector表中查询每个source对应的chunk数量
     */
    private Map<String, Integer> getChunkCountMap() {
        try {
            String sql = String.format(
                    "SELECT metadata->>'source' AS source, COUNT(*) AS cnt FROM %s GROUP BY metadata->>'source'",
                    tableName
            );
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            return rows.stream().collect(Collectors.toMap(
                    row -> String.valueOf(row.get("source")),
                    row -> ((Number) row.get("cnt")).intValue()
            ));
        } catch (Exception e) {
            log.warn("查询PGVector chunk数量失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String getFileExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < filename.length() - 1) {
            return filename.substring(dotIndex + 1).toLowerCase();
        }
        return "";
    }

    private String loadDocumentContent(File file, String filename) throws IOException {
        String extension = filename.substring(filename.lastIndexOf(".")).toLowerCase();

        switch (extension) {
            case ".txt":
            case ".md":
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            case ".pdf":
                log.warn("PDF处理暂未完全实现，建议先转换为txt格式");
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            default:
                throw new BusinessException(ResultCode.UNSUPPORTED_FILE_TYPE, extension);
        }
    }

    /**
     * 获取上传目录的绝对路径
     */
    private Path getUploadDirectory() {
        Path path = Paths.get(uploadPath);
        log.info("上传目录配置: {}, 绝对路径: {}", uploadPath, path.toAbsolutePath());
        return path;
    }

    /**
     * 清理文件名
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unknown.txt";
        }

        String safeName = filename.replaceAll("\\.\\.", "_")
                .replaceAll("/", "_")
                .replaceAll("\\\\", "_");

        int lastSeparator = Math.max(safeName.lastIndexOf('/'), safeName.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            safeName = safeName.substring(lastSeparator + 1);
        }

        if (safeName.isEmpty()) {
            safeName = "document_" + System.currentTimeMillis() + ".txt";
        }

        return safeName;
    }

    /**
     * 创建新分类
     */
    public void createCategory(String categoryName, String description) {
        try {
            if (categoryName == null || categoryName.trim().isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "分类名称不能为空");
            }
            String normalizedName = categoryName.trim();
            LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Category::getName, normalizedName);
            if (categoryMapper.selectCount(wrapper) > 0) {
                throw new BusinessException(ResultCode.CATEGORY_EXISTS);
            }
            Path categoryDir = Paths.get(uploadRootPath, normalizedName);
            if (!Files.exists(categoryDir)) {
                Files.createDirectories(categoryDir);
                log.info("创建物理文件夹: {}", categoryDir.toAbsolutePath());
            }
            Category category = new Category();
            category.setName(normalizedName);
            category.setDescription(description);
            categoryMapper.insert(category);
            log.info("分类创建成功: {}", normalizedName);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.CATEGORY_EXISTS);
        }
    }

    /**
     * 删除分类
     */
    public void deleteCategory(String categoryName) {
        try {
            if (categoryName == null || categoryName.trim().isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "分类名称不能为空");
            }

            // 检查该分类下是否有文档
            LambdaQueryWrapper<DocumentMetadata> docWrapper = new LambdaQueryWrapper<>();
            docWrapper.eq(DocumentMetadata::getCategory, categoryName)
                    .eq(DocumentMetadata::getStatus, "active");
            Long docCount = documentMetadataMapper.selectCount(docWrapper);

            if (docCount != null && docCount > 0) {
                throw new BusinessException(ResultCode.CATEGORY_NOT_EMPTY);
            }

            // 删除物理文件夹
            Path categoryDir = Paths.get(uploadRootPath, categoryName);
            if (Files.exists(categoryDir)) {
                try (var stream = Files.list(categoryDir)) {
                    if (stream.findAny().isPresent()) {
                        throw new BusinessException(ResultCode.CATEGORY_NOT_EMPTY, "物理文件夹不为空");
                    }
                }
                Files.delete(categoryDir);
                log.info("删除物理文件夹: {}", categoryDir.toAbsolutePath());
            }

            // 删除数据库记录
            LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Category::getName, categoryName);
            categoryMapper.delete(wrapper);

            log.info("分类删除成功: {}", categoryName);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_EMPTY, "删除分类失败");
        }
    }

    /**
     * 获取所有分类列表
     */
    public List<String> getAllCategoriesFromTable() {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Category::getCreateTime);
        List<Category> categories = categoryMapper.selectList(wrapper);
        return categories.stream().map(Category::getName).collect(Collectors.toList());
    }

    /**
     * 根据分类名称获取该分类下的所有文件列表（✅ 改为 MyBatis-Plus）
     */
    public List<DocumentFileDTO> getFilesByCategory(String categoryName) {
        // 使用 MyBatis-Plus 查询文档
        LambdaQueryWrapper<DocumentMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentMetadata::getCategory, categoryName)
                .eq(DocumentMetadata::getStatus, "active")
                .orderByDesc(DocumentMetadata::getUploadTime);

        List<DocumentMetadata> list = documentMetadataMapper.selectList(wrapper);

        // 批量查询 chunk 数量
        Map<String, Integer> chunkCountMap = getChunkCountMap();

        return list.stream().map(metadata -> {
            int chunkCount = chunkCountMap.getOrDefault(metadata.getFileName(), 0);
            return new DocumentFileDTO(
                    metadata.getId(),
                    metadata.getFileName(),
                    metadata.getFilePath(),
                    metadata.getFileSize(),
                    metadata.getFileType(),
                    chunkCount,
                    metadata.getUploadTime()
            );
        }).collect(Collectors.toList());
    }

    /**
     * 检查分类是否存在
     */
    public boolean categoryExists(String categoryName) {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getName, categoryName);
        return categoryMapper.selectCount(wrapper) > 0;
    }

    /**
     * 删除文档
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long documentId) {
        try {
            DocumentMetadata metadata = documentMetadataMapper.selectById(documentId);
            if (metadata == null) {
                throw new BusinessException(ResultCode.DOCUMENT_NOT_FOUND);
            }
            String fileName = metadata.getFileName();
            String filePath = metadata.getFilePath();
            String category = metadata.getCategory();
            log.info("开始删除文档: id={}, fileName={}", documentId, fileName);
            // 删除向量库
            deleteVectorsByFileName(fileName);
            // 删除物理文件
            Path physicalFile = Paths.get(filePath);
            if (Files.exists(physicalFile)) {
                Files.delete(physicalFile);
                log.info("物理文件已删除: {}", physicalFile);
            } else {
                log.warn("物理文件不存在，跳过删除: {}", physicalFile);
            }
            // 删除元数据
            int rows = documentMetadataMapper.deleteById(documentId);
            if (rows == 0) {
                throw new BusinessException(ResultCode.DOCUMENT_NOT_FOUND);
            }
            // 清理空分类
            if (!hasFilesInCategory(category)) {
                deleteEmptyCategory(category);
                log.info("分类已无文件，自动删除空分类: {}", category);
            }
            log.info("文档删除完成: id={}, fileName={}", documentId, fileName);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.VECTOR_DELETE_ERROR, "删除向量失败");
        }
    }

    /**
     * 根据文件名删除向量库中的片段
     */
    private void deleteVectorsByFileName(String fileName) {
        String sql = "DELETE FROM embeddings WHERE metadata->>'source' = ?";
        int deleted = jdbcTemplate.update(sql, fileName);
        log.info("从向量库删除了 {} 个与文件 '{}' 相关的向量", deleted, fileName);
    }

    /**
     * 检查某个分类下是否还有活跃文件
     */
    private boolean hasFilesInCategory(String category) {
        LambdaQueryWrapper<DocumentMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentMetadata::getCategory, category)
                .eq(DocumentMetadata::getStatus, "active");
        return documentMetadataMapper.selectCount(wrapper) > 0;
    }

    /**
     * 删除空分类
     */
    private void deleteEmptyCategory(String category) throws IOException {
        // 删除分类记录
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getName, category);
        int rows = categoryMapper.delete(wrapper);
        if (rows > 0) {
            log.info("分类数据库记录已删除: {}", category);
        }

        // 删除物理文件夹
        Path categoryDir = Paths.get(uploadRootPath, category);
        if (Files.exists(categoryDir)) {
            try (var stream = Files.list(categoryDir)) {
                if (!stream.findAny().isPresent()) {
                    Files.delete(categoryDir);
                    log.info("空物理文件夹已删除: {}", categoryDir);
                } else {
                    log.warn("物理文件夹不为空，保留: {}", categoryDir);
                }
            }
        }
    }
}