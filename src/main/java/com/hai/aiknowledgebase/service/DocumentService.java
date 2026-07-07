package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.DocumentFileDTO;
import com.hai.aiknowledgebase.dto.DocumentInfo;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

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
                          JdbcTemplate jdbcTemplate) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 上传文档并按分类存储
     * @param file 上传的文件
     * @param category 分类名称（如：财务报告、技术文档），为 null 时存入"未分类"
     * @return 切分后的文本块数量
     */
    public int uploadDocument(MultipartFile file, String category) throws IOException {
        String originalFilename = file.getOriginalFilename();
        log.info("开始处理文档: {}, 分类: {}", originalFilename, category);

        // 1. 处理分类名称
        String normalizedCategory = (category == null || category.trim().isEmpty())
                ? "未分类"
                : category.trim();

        // 2. 生成安全的文件名（防止路径穿越攻击）
        String safeFilename = sanitizeFilename(originalFilename);
        String uniqueFilename = generateUniqueFilename(safeFilename); // 添加UUID前缀，防止重名覆盖

        // 3. 动态构建分类路径：uploads/分类/yyyy-MM/
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Path uploadDir = Paths.get(uploadRootPath, normalizedCategory, dateDir);

        // 4. 创建目录（如果不存在）
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
            log.info("创建上传目录: {}", uploadDir);
        }

        // 5. 保存文件到磁盘
        Path filePath = uploadDir.resolve(uniqueFilename);
        try {
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("文件已保存到: {}", filePath);
        } catch (IOException e) {
            log.error("文件复制失败: {}", e.getMessage(), e);
            throw e;
        }

        // 6. 记录文件元数据到 document_metadata 表（新增）
        saveDocumentMetadata(originalFilename, filePath.toString(), normalizedCategory, file.getSize());

        // 7. 加载文档内容
        String content = loadDocumentContent(filePath.toFile(), safeFilename);

        // 8. 构建 Document（携带分类元数据）
        Document document = Document.from(
                content,
                Metadata.from("source", safeFilename)
                        .put("category", normalizedCategory)   // 👈 将分类注入元数据
                        .put("filepath", filePath.toString())
        );

        // 9. 切分文档为文本块
        DocumentSplitter splitter = new DocumentByParagraphSplitter(chunkSize, chunkOverlap);
        List<TextSegment> segments = splitter.split(document);

        // 10. 向量化入库（每个 TextSegment 自动携带父 Document 的元数据）
        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }

        // 11. 确保分类在 categories 表中存在（兜底）
        ensureCategoryExists(normalizedCategory);

        log.info("文档处理完成: {}, 分类: {}, 生成 {} 个文本块",
                safeFilename, normalizedCategory, segments.size());
        return segments.size();
    }

    /**
     * 生成唯一文件名（防止重名覆盖）
     */
    private String generateUniqueFilename(String originalFilename) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return uuid + "_" + originalFilename;
    }

    /**
     * 记录文件元数据到数据库
     */
    private void saveDocumentMetadata(String filename, String filePath, String category, long fileSize) {
        String sql = """
            INSERT INTO document_metadata 
            (file_name, file_path, category, file_size, upload_time) 
            VALUES (?, ?, ?, ?, NOW())
            """;
        jdbcTemplate.update(sql, filename, filePath, category, fileSize);
        log.info("文件元数据已记录: {}", filename);
    }

    /**
     * 确保分类在 categories 表中存在（兜底）
     */
    private void ensureCategoryExists(String categoryName) {
        String checkSql = "SELECT COUNT(*) FROM categories WHERE name = ?";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, categoryName);
        if (count == null || count == 0) {
            jdbcTemplate.update("INSERT INTO categories (name) VALUES (?)", categoryName);
            log.info("自动创建分类记录: {}", categoryName);
        }
    }


    /**
     * 查询所有上传的历史文档列表
     */
    public List<DocumentInfo> listDocuments() {
        log.info("查询历史文档列表");

        // 从PGVector表中查询每个文档的chunk数量
        Map<String, Integer> chunkCountMap = getChunkCountMap();

        // 扫描上传目录获取文件信息
        Path uploadDir = getUploadDirectory();
        List<DocumentInfo> documents = new ArrayList<>();

        if (!Files.exists(uploadDir)) {
            log.warn("上传目录不存在: {}", uploadDir);
            return documents;
        }

        File[] files = uploadDir.toFile().listFiles();
        if (files == null) {
            return documents;
        }

        for (File file : files) {
            if (file.isFile()) {
                String filename = file.getName();
                String extension = getFileExtension(filename);
                int chunkCount = chunkCountMap.getOrDefault(filename, 0);

                documents.add(new DocumentInfo(
                    filename,
                    file.length(),
                    extension,
                    file.lastModified(),
                    chunkCount
                ));
            }
        }

        log.info("查询到 {} 个历史文档", documents.size());
        return documents;
    }

    /**
     * 从PGVector表中查询每个source对应的chunk数量
     */
    private Map<String, Integer> getChunkCountMap() {
        try {
            String sql = String.format(
                "SELECT embedding->>'source' AS source, COUNT(*) AS cnt FROM %s WHERE embedding ? 'source' GROUP BY embedding->>'source'",
                tableName
            );
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            return rows.stream().collect(Collectors.toMap(
                row -> String.valueOf(row.get("source")),
                row -> ((Number) row.get("cnt")).intValue()
            ));
        } catch (Exception e) {
            log.warn("查询PGVector chunk数量失败，尝试备用查询: {}", e.getMessage());
            try {
                // 备用查询：兼容不同PGVector版本的metadata存储方式
                String sql = String.format(
                    "SELECT metadata->>'source' AS source, COUNT(*) AS cnt FROM %s WHERE metadata ? 'source' GROUP BY metadata->>'source'",
                    tableName
                );
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

                return rows.stream().collect(Collectors.toMap(
                    row -> String.valueOf(row.get("source")),
                    row -> ((Number) row.get("cnt")).intValue()
                ));
            } catch (Exception e2) {
                log.warn("备用查询也失败，chunk数量将显示为0: {}", e2.getMessage());
                return Map.of();
            }
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
                // PDF处理需要额外的依赖，这里简化处理
                log.warn("PDF处理暂未完全实现，建议先转换为txt格式");
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);

            default:
                throw new IllegalArgumentException("不支持的文件格式: " + extension);
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
     * 清理文件名，移除路径遍历字符和特殊字符
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
     * 创建新分类（同时创建物理文件夹）
     */
    public void createCategory(String categoryName, String description) throws IOException {
        // 1. 校验分类名称是否为空
        if (categoryName == null || categoryName.trim().isEmpty()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }

        String normalizedName = categoryName.trim();

        // 2. 检查数据库中是否已存在该分类
        String checkSql = "SELECT COUNT(*) FROM categories WHERE name = ?";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, normalizedName);
        if (count != null && count > 0) {
            throw new RuntimeException("分类已存在: " + normalizedName);
        }

        // 3. 创建物理文件夹
        Path categoryDir = Paths.get(uploadRootPath, normalizedName);
        if (!Files.exists(categoryDir)) {
            Files.createDirectories(categoryDir);
            log.info("创建物理文件夹: {}", categoryDir.toAbsolutePath());
        } else {
            log.warn("物理文件夹已存在: {}", categoryDir.toAbsolutePath());
        }

        // 4. 记录到 categories 表
        String insertSql = "INSERT INTO categories (name, description) VALUES (?, ?)";
        jdbcTemplate.update(insertSql, normalizedName, description);

        log.info("分类创建成功: {}", normalizedName);
    }

    /**
     * 删除分类（物理删除文件夹 + 逻辑删除）
     * 注意：如果文件夹内有文件，需要做级联处理，此处给出两种选择
     */
    public void deleteCategory(String categoryName) throws IOException {
        // 1. 检查该分类下是否有文档（关联 document_metadata 表）
        String checkSql = "SELECT COUNT(*) FROM document_metadata WHERE category = ?";
        Integer docCount = jdbcTemplate.queryForObject(checkSql, Integer.class, categoryName);

        if (docCount != null && docCount > 0) {
            // 方案A：禁止删除，提示用户先清空文件
            throw new RuntimeException("分类下还有 " + docCount + " 个文档，请先删除或迁移文件后再删除分类");
            // 方案B：级联删除（物理删文件 + 删向量 + 删数据库记录），这里暂不展开
        }

        // 2. 删除物理文件夹（如果为空）
        Path categoryDir = Paths.get(uploadRootPath, categoryName);
        if (Files.exists(categoryDir)) {
            // 确认文件夹为空（防止误删非空目录）
            try (var stream = Files.list(categoryDir)) {
                if (stream.findAny().isPresent()) {
                    throw new RuntimeException("物理文件夹不为空，请手动清理");
                }
            }
            Files.delete(categoryDir);
            log.info("删除物理文件夹: {}", categoryDir.toAbsolutePath());
        }

        // 3. 删除数据库记录
        jdbcTemplate.update("DELETE FROM categories WHERE name = ?", categoryName);

        // 4. 注意：document_metadata 表中的 category 数据不会被删除（外键设为 ON DELETE SET NULL 或保留）
        log.info("分类删除成功: {}", categoryName);
    }

    public List<String> getAllCategoriesFromTable() {
        String sql = "SELECT name FROM categories ORDER BY create_time DESC";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    /**
     * 根据分类名称获取该分类下的所有文件列表
     * @param categoryName 分类名称
     * @return 文件列表
     */
    public List<DocumentFileDTO> getFilesByCategory(String categoryName) {
        String sql = """
            SELECT id, file_name, file_path, file_size, file_type, upload_time,
                   (SELECT COUNT(*) FROM embeddings WHERE metadata->>'source' = d.file_name) as chunk_count
            FROM document_metadata d
            WHERE category = ? AND status = 'active'
            ORDER BY upload_time DESC
            """;

        return jdbcTemplate.query(sql, new Object[]{categoryName}, (rs, rowNum) ->
                new DocumentFileDTO(
                        rs.getLong("id"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getLong("file_size"),
                        rs.getString("file_type"),
                        rs.getInt("chunk_count"),
                        rs.getTimestamp("upload_time").toLocalDateTime()
                )
        );
    }

/*    *//**
     * 检查某个分类下是否有文件（用于删除分类时校验）
     *//*
    public boolean hasFilesInCategory(String categoryName) {
        String sql = "SELECT COUNT(*) FROM document_metadata WHERE category = ? AND status = 'active'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, categoryName);
        return count != null && count > 0;
    }*/

    /**
     * 检查分类是否存在
     */
    public boolean categoryExists(String categoryName) {
        String sql = "SELECT COUNT(*) FROM categories WHERE name = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, categoryName);
        return count != null && count > 0;
    }

    /**
     * 删除文档（硬删除）
     * @param documentId 文档ID
     * @throws IOException 文件删除失败时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long documentId) throws IOException {
        // 1. 查询文件元数据
        String selectSql = "SELECT id, file_name, file_path, category FROM document_metadata WHERE id = ?";
        Map<String, Object> doc = jdbcTemplate.queryForMap(selectSql, documentId);

        String fileName = (String) doc.get("file_name");
        String filePath = (String) doc.get("file_path");
        String category = (String) doc.get("category");

        log.info("开始删除文档: id={}, fileName={}, filePath={}", documentId, fileName, filePath);

        // 2. 从向量库删除所有相关片段（关键步骤）
        deleteVectorsByFileName(fileName);

        // 3. 删除物理文件（如果存在）
        Path physicalFile = Paths.get(filePath);
        if (Files.exists(physicalFile)) {
            Files.delete(physicalFile);
            log.info("物理文件已删除: {}", physicalFile);
        } else {
            log.warn("物理文件不存在，跳过删除: {}", physicalFile);
        }

        // 4. 删除 document_metadata 表中的记录
        String deleteSql = "DELETE FROM document_metadata WHERE id = ?";
        int rows = jdbcTemplate.update(deleteSql, documentId);
        if (rows == 0) {
            throw new RuntimeException("文档记录不存在或已被删除");
        }

        // 5. （可选）如果分类下已无文件，则删除空分类
        if (!hasFilesInCategory(category)) {
            deleteEmptyCategory(category);
            log.info("分类已无文件，自动删除空分类: {}", category);
        }

        log.info("文档删除完成: id={}, fileName={}", documentId, fileName);
    }

    /**
     * 根据文件名删除向量库中的所有相关片段（直接执行 SQL）
     * 适配 PGVector 的 metadata 为 JSONB 类型
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
        String sql = "SELECT COUNT(*) FROM document_metadata WHERE category = ? AND status = 'active'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, category);
        return count != null && count > 0;
    }

    /**
     * 删除空分类（同时删除 categories 表和物理文件夹）
     */
    private void deleteEmptyCategory(String category) throws IOException {
        // 删除数据库记录
        String sql = "DELETE FROM categories WHERE name = ?";
        int rows = jdbcTemplate.update(sql, category);
        if (rows > 0) {
            log.info("分类数据库记录已删除: {}", category);
        }

        // 删除物理文件夹（如果为空）
        Path categoryDir = Paths.get(uploadRootPath, category);
        if (Files.exists(categoryDir)) {
            // 检查文件夹是否真的为空（防止误删）
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
