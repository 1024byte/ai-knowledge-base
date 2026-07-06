package com.hai.aiknowledgebase.service;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public DocumentService(EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel,
                          JdbcTemplate jdbcTemplate) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
    }

    public int uploadDocument(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        log.info("开始处理文档: {}", filename);

        String safeFilename = sanitizeFilename(filename);
        Path uploadDir = getUploadDirectory();

        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
            log.info("创建上传目录: {}", uploadDir);
        }

        Path filePath = uploadDir.resolve(safeFilename);

        try {
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("文件已保存到: {}", filePath);
        } catch (IOException e) {
            log.error("文件复制失败: {}", e.getMessage(), e);
            throw e;
        }

        String content = loadDocumentContent(filePath.toFile(), safeFilename);

        Document document = Document.from(content, Metadata.from("source", safeFilename));

        DocumentSplitter splitter = new DocumentByParagraphSplitter(chunkSize, chunkOverlap);
        List<TextSegment> segments = splitter.split(document);

        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }

        log.info("文档处理完成: {}, 生成 {} 个文本块", safeFilename, segments.size());
        return segments.size();
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
}
