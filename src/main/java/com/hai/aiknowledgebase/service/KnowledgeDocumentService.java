package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hai.aiknowledgebase.common.CustomDocument;
import com.hai.aiknowledgebase.common.HashType;
import com.hai.aiknowledgebase.common.Role;
import com.hai.aiknowledgebase.dto.*;
import com.hai.aiknowledgebase.entity.KnowledgeBase;
import com.hai.aiknowledgebase.entity.KnowledgeDocument;
import com.hai.aiknowledgebase.entity.KnowledgeDocumentChunk;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.KnowledgeDocumentChunkMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeDocumentMapper;
import com.hai.aiknowledgebase.security.SecurityUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hai.aiknowledgebase.common.FileUtils.*;

/**
 * <h2>知识库文件管理服务</h2>
 *
 * <p>负责知识库下文件的全生命周期管理，包括上传、查询、删除以及内部状态更新。</p>
 *
 * <h3>解析状态流转</h3>
 * <pre>
 * 待处理(0) → 处理中(1) → 已完成(2)
 *                  ↓
 *              失败(3) → 可重试 → 待处理(0)
 * </pre>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>文件上传</b>：接收文件 → 业务校验 → 存储 → 元数据入库 → 事务提交后触发异步向量化</li>
 *   <li><b>文件查询</b>：按知识库 ID 查询文件列表、分页查询、按文件哈希查询（秒传去重）</li>
 *   <li><b>文件删除</b>：逻辑删除，自动关联清理向量数据（待后续向量清理服务）</li>
 *   <li><b>状态管理</b>：内部方法供向量化流程更新解析状态</li>
 * </ul>
 *
 * @see KnowledgeDocumentMapper MyBatis-Plus 数据访问
 * @see KnowledgeBaseService 知识库查询服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeDocumentChunkMapper knowledgeDocumentChunkMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KbPermissionService kbPermissionService;
    private final DocumentParserService documentParserService;
    private final DocumentChunkerService documentChunkerService;

    /** 文件上传根目录，默认 "./uploads/knowledge" */
    @Value("${file.upload.root:./uploads}")
    private String uploadRootPath;

    /** 文件上传大小上限（MB），默认 50MB */
    @Value("${knowledge.upload.max-size-mb:50}")
    private long maxUploadSizeMb;

    /** 文档解析输出目录，默认 "./parse" */
    @Value("${document.parse.output-dir:./parse}")
    private String parseOutputDir;

    /** 切片时解析文件最大大小（MB），默认 20MB，防止大文件 OOM */
    @Value("${document.chunk.max-file-size-mb:20}")
    private int maxChunkFileSizeMb;

    /** 自引用（通过 Proxy 调用自身异步方法，解决 AOP 同类调用失效问题） */
    @Lazy
    @Autowired
    private KnowledgeDocumentService self;

    /** 嵌入模型（ONNX 本地模型 paraphrase-multilingual-MiniLM-L12-v2） */
    private final EmbeddingModel embeddingModel;

    /** Token 计数估算器（用于片段编辑/合并/拆分后重算 token 数） */
    private final TokenCountEstimator tokenCountEstimator;

    /** PGVector 向量存储 */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** 文档哈希去重服务 */
    private final DocumentHashService documentHashService;

    /** 混合检索服务（BM25 关键词索引） */
    private final HybridSearchService hybridSearchService;

    /** JDBC 模板（用于直接清理 PGVector 旧向量，避开 Filter API 版本差异） */
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** PGVector 向量表名 */
    @Value("${vectorstore.pgvector.table-name:embeddings}")
    private String vectorTableName;

    /** 支持的文件扩展名（小写，不含点号） */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "txt", "md", "html", "htm", "markdown"
    );

    /** 标题最大长度 */
    private static final int MAX_TITLE_LENGTH = 100;

    /** 时间格式化：YYYY-MM-DD HH:mm:ss */
    private static final DateTimeFormatter DATETIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss").toFormatter();

    // ======================== 文件上传 ========================

    /**
     * <h3>上传知识库文件</h3>
     *
     * <p>完整上传流程：</p>
     * <ol>
     *   <li><b>业务校验</b>：知识库存在性、文件格式、大小、标题长度</li>
     *   <li><b>秒传去重</b>：SHA-256 哈希判断是否已存在相同文件</li>
     *   <li><b>目录生成</b>：构建 {@code {uploadRoot}/{kbId}/{yyyy-MM}/} 目录结构</li>
     *   <li><b>文件存储</b>：将 MultipartFile 写入磁盘</li>
     *   <li><b>元数据入库</b>：状态初始化为 0（待处理）</li>
     *   <li><b>注册事务回调</b>：主事务提交后触发异步向量化</li>
     * </ol>
     *
     * @param file          上传的文档文件
     * @param title         文档标题（用户输入）
     * @param kbId          所属知识库 ID
     * @return 上传结果（含文件元信息和解析/向量化状态）
     */
    @Transactional
    public KnowledgeDocumentUploadVO upload(MultipartFile file, String title, Long kbId) {
        // ===== 1. 知识库存在性校验 =====
        KnowledgeBase kb = knowledgeBaseService.findById(kbId);
        if (kb == null) {
            throw new BusinessException(400, "知识库不存在");
        }
        kbPermissionService.checkPermission(SecurityUtils.getCurrentUserId(), kbId, Role.EDITOR);

        // ===== 2. 文件基础校验 =====
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BusinessException(400, "文件名不能为空");
        }

        // ===== 3. 文件格式校验 =====
        String fileExt = getFileExtension(originalFilename);
        if (!SUPPORTED_EXTENSIONS.contains(fileExt)) {
            throw new BusinessException(400, "不支持的文件格式");
        }

        // ===== 4. 文件大小校验 =====
        long fileSize = file.getSize();
        long maxBytes = maxUploadSizeMb * 1024 * 1024;
        if (fileSize > maxBytes) {
            throw new BusinessException(400,
                    String.format("文件大小超过限制（最大 %dMB）", maxUploadSizeMb));
        }

        // ===== 5. 标题长度校验 =====
        if (title == null || title.isBlank()) {
            throw new BusinessException(400, "标题不能为空");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(400, "标题不超过 100 个字符");
        }

        log.info("开始上传知识库文件: kbId={}, title={}, fileName={}, size={}",
                kb.getId(), title, originalFilename, fileSize);

        // ===== 6. SHA-256 秒传去重 =====
        String fileHash;
        try {
            fileHash = DigestUtils.sha256Hex(file.getInputStream());
        } catch (IOException e) {
            log.error("计算文件哈希失败", e);
            throw new BusinessException(500, "文件处理失败");
        }

        KnowledgeDocument existing = findByFileHash(fileHash);
        if (existing != null) {
            log.info("文件已存在（秒传命中）: existingId={}", existing.getId());
            return toUploadVO(existing, kb.getName());
        }

        // ===== 7. 生成安全文件名并存储 =====
        // 注意：文件写入在数据库事务之前，如果后续 DB 操作失败，必须清理已落盘的文件
        String safeFilename = sanitizeFilename(originalFilename);
        String uniqueFilename = generateUniqueFilename(safeFilename);

        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Path uploadDir = Paths.get(uploadRootPath, String.valueOf(kb.getId()), dateDir);
        try {
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
        } catch (IOException e) {
            log.error("创建上传目录失败: {}", uploadDir, e);
            throw new BusinessException(500, "文件存储失败");
        }

        Path filePath = uploadDir.resolve(uniqueFilename);
        try {
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("文件已保存到: {}", filePath);
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BusinessException(500, "文件存储失败");
        }

        // ===== 8. 元数据入库 + 失败时清理文件 =====
        // 文件已落盘，DB 插入可能因约束冲突立即失败，也可能在事务提交时失败。
        // 注册事务回调：如果事务回滚，删除已写入磁盘的文件，保证文件系统与数据库一致。
        final Path finalFilePath = filePath;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    log.warn("事务回滚（status={}），清理磁盘文件: {}", status, finalFilePath);
                    try {
                        Files.deleteIfExists(finalFilePath);
                        log.info("磁盘文件已清理: {}", finalFilePath);
                    } catch (IOException e) {
                        log.error("清理磁盘文件失败: {}", finalFilePath, e);
                    }
                }
            }
        });

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kb.getId());
        doc.setTitle(title.trim());
        doc.setFileName(originalFilename);
        doc.setFileExt(fileExt);
        doc.setFileSize(fileSize);
        doc.setFileUrl(filePath.toString());
        doc.setFileHash(fileHash);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        doc.setStatus(0);
        try {
            knowledgeDocumentMapper.insert(doc);
        } catch (Exception e) {
            // DB 插入立即失败：事务回滚会触发上面的 afterCompletion 清理文件
            log.error("知识库文件入库失败: fileName={}", originalFilename, e);
            throw new BusinessException(500, "文件入库失败");
        }

        log.info("知识库文件记录已创建: id={}, kbId={}", doc.getId(), kb.getId());

        // ===== 9. 注册事务成功回调（异步向量化） =====
        // 复用同一个 TransactionSynchronization：文件清理逻辑已在步骤 8 的回调中处理，
        // 此处仅追加事务提交后的业务逻辑注册
        final Long createdDocId = doc.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("事务已提交，可触发异步向量化: docId={}", createdDocId);
            }
        });

        return toUploadVO(doc, kb.getName());
    }

    // ======================== 查询 ========================

    /**
     * <h3>获取文件列表（支持按知识库筛选）</h3>
     *
     * <p>按创建时间降序排列，自动过滤已删除的文件。{@code kbId} 为 {@code null} 时查询所有知识库。</p>
     *
     * @param kbId 知识库 ID（可选，null 时查询所有）
     * @return 文件列表
     */
    public List<KnowledgeDocumentVO> listByKbId(Long kbId) {
        Long userId = SecurityUtils.getCurrentUserId();
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();

        if (kbId != null) {
            kbPermissionService.checkPermission(userId, kbId, Role.VIEWER);
            wrapper.eq(KnowledgeDocument::getKbId, kbId);
        } else {
            List<Long> visibleKbIds = kbPermissionService.getVisibleKbIds(userId);
            if (visibleKbIds.isEmpty()) {
                return List.of();
            }
            wrapper.in(KnowledgeDocument::getKbId, visibleKbIds);
        }
        wrapper.orderByDesc(KnowledgeDocument::getCreatedAt);

        List<KnowledgeDocument> list = knowledgeDocumentMapper.selectList(wrapper);
        return list.stream().map(this::toVO).collect(Collectors.toList());
    }

    /**
     * <h3>分页查询知识库文件</h3>
     *
     * <p>按创建时间降序排列，自动过滤已删除的文件。支持按文件名模糊搜索、按状态筛选。
     * {@code kbId} 为 {@code null} 时查询所有知识库。</p>
     *
     * @param kbId     知识库 ID（可选，null 时查询所有）
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页大小
     * @param fileName 文件名关键字（可选，模糊匹配）
     * @param status   解析状态（可选，0-待处理/1-处理中/2-已完成/3-失败）
     * @return 分页结果
     */
    public Page<KnowledgeDocumentVO> pageByKbId(Long kbId, int pageNum, int pageSize,
                                                 String fileName, Integer status) {
        Long userId = SecurityUtils.getCurrentUserId();
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();

        if (kbId != null) {
            kbPermissionService.checkPermission(userId, kbId, Role.VIEWER);
            wrapper.eq(KnowledgeDocument::getKbId, kbId);
        } else {
            List<Long> visibleKbIds = kbPermissionService.getVisibleKbIds(userId);
            if (visibleKbIds.isEmpty()) {
                Page<KnowledgeDocumentVO> emptyPage = new Page<>(pageNum, pageSize, 0);
                emptyPage.setRecords(List.of());
                return emptyPage;
            }
            wrapper.in(KnowledgeDocument::getKbId, visibleKbIds);
        }
        wrapper.like(fileName != null && !fileName.isBlank(),
                        KnowledgeDocument::getFileName, fileName)
                .eq(status != null, KnowledgeDocument::getStatus, status)
                .orderByDesc(KnowledgeDocument::getCreatedAt);

        Page<KnowledgeDocument> entityPage = knowledgeDocumentMapper.selectPage(
                new Page<>(pageNum, pageSize), wrapper);

        List<KnowledgeDocumentVO> voList = entityPage.getRecords().stream()
                .map(this::toVO).collect(Collectors.toList());

        Page<KnowledgeDocumentVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * <h3>根据 ID 获取文件详情</h3>
     *
     * @param id 文件 ID
     * @return 文件详情
     * @throws BusinessException 文件不存在或已删除时抛出
     */
    public KnowledgeDocumentVO getById(Long id) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(404, "文件不存在");
        }
        checkDocumentPermission(doc, Role.VIEWER);
        return toVO(doc);
    }

    /**
     * <h3>根据文件哈希查询（秒传去重）</h3>
     *
     * <p>判断系统中是否已存在相同哈希的文件，用于秒传场景。</p>
     *
     * @param fileHash 文件 MD5/SHA256
     * @return 已存在的文件（null 表示不存在）
     */
    public KnowledgeDocument findByFileHash(String fileHash) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getFileHash, fileHash)
                .last("LIMIT 1");
        return knowledgeDocumentMapper.selectOne(wrapper);
    }

    /**
     * <h3>文档资源级鉴权</h3>
     *
     * <p>根据文档所属知识库（{@code kbId}）校验当前登录用户是否具备指定角色权限。</p>
     *
     * @param doc          文档实体（已存在，kbId 不能为空）
     * @param requiredRole 所需的最低角色
     * @throws BusinessException 未登录或权限不足时抛出
     */
    private void checkDocumentPermission(KnowledgeDocument doc, Role requiredRole) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        kbPermissionService.checkPermission(userId, doc.getKbId(), requiredRole);
    }

    // ======================== 删除 ========================

    /**
     * <h3>删除文档（逻辑删除 + 关联资源清理）</h3>
     *
     * <p>执行逻辑删除的同时清理物理文件、切片、向量及哈希等派生资源，避免磁盘与向量库泄露。</p>
     *
     * <h4>删除步骤</h4>
     * <ol>
     *   <li>查询文档记录（含 is_deleted=1，用原生查询绕过逻辑删除过滤）</li>
     *   <li>幂等判断：已逻辑删除的记录返回 400</li>
     *   <li>清理关联资源（best-effort）：
     *     <ul>
     *       <li>物理文件：原始文件（{@code file_url}）、解析文件（{@code parsed_text_url}）</li>
     *       <li>切片数据：{@code knowledge_document_chunk}</li>
     *       <li>向量数据：PGVector 向量 + BM25 关键词索引</li>
     *       <li>文件哈希去重记录</li>
     *     </ul>
     *   </li>
     *   <li>逻辑删除数据库记录（is_deleted=1）</li>
     * </ol>
     *
     * <h4>文件清理策略</h4>
     * <p>仅处理本地文件路径（非 http(s) 开头），文件不存在不报错（幂等）。
     * 远端 URL 文件不处理（无法删除服务端资源）。</p>
     *
     * @param id 文档 ID
     * @return 被删除的文档信息（isDeleted=1）
     * @throws BusinessException 文件不存在或已删除时抛出
     */
    @Transactional
    public KnowledgeDocumentVO delete(Long id) {
        // ===== 1. 查询文档（绕过逻辑删除过滤） =====
        KnowledgeDocument doc = knowledgeDocumentMapper.selectByIdIncludeDeleted(id);
        if (doc == null) {
            throw new BusinessException(404, "文件不存在");
        }
        checkDocumentPermission(doc, Role.EDITOR);

        // ===== 2. 幂等判断 =====
        if (Integer.valueOf(1).equals(doc.getIsDeleted())) {
            throw new BusinessException(400, "文件已删除");
        }

        log.info("删除知识库文件: id={}, fileName={}, fileUrl={}, parsedTextUrl={}",
                id, doc.getFileName(), doc.getFileUrl(), doc.getParsedTextUrl());

        // ===== 3. 清理关联资源（物理文件 + 切片 + 向量 + 哈希） =====
        cleanupDocumentResources(doc);

        // ===== 4. 逻辑删除数据库记录 =====
        knowledgeDocumentMapper.deleteById(id);
        log.info("知识库文件已逻辑删除: id={}, fileName={}", id, doc.getFileName());

        // 标记已删除，让返回 VO 反映删除状态
        doc.setIsDeleted(1);
        return toVO(doc);
    }

    /**
     * <h3>清理文档关联资源（best-effort）</h3>
     *
     * <p>清理文档的所有派生资源，供单个删除与知识库级联删除复用：</p>
     * <ol>
     *   <li>物理文件：原始上传文件 + 解析输出文件</li>
     *   <li>切片数据：{@code knowledge_document_chunk} 物理删除</li>
     *   <li>向量数据：PGVector 向量 + BM25 关键词索引</li>
     *   <li>文件哈希去重记录</li>
     * </ol>
     *
     * @param doc 待清理的文档实体
     */
    private void cleanupDocumentResources(KnowledgeDocument doc) {
        deleteFileIfLocal(doc.getFileUrl(), "原始文件");
        deleteFileIfLocal(doc.getParsedTextUrl(), "解析文件");

        knowledgeDocumentChunkMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentChunk>()
                .eq(KnowledgeDocumentChunk::getDocumentId, doc.getId()));

        jdbcTemplate.update(
                "DELETE FROM " + vectorTableName + " WHERE metadata->>'document_id' = ?",
                String.valueOf(doc.getId()));

        String fileName = doc.getFileName() != null ? doc.getFileName() : "document";
        String kbPrefix = doc.getKbId() != null ? doc.getKbId() + "_" : "";
        // 清理旧 BM25 索引：先按新格式 "kbId_fileName_" 清理，再兼容旧格式 "fileName_"
        hybridSearchService.getKeywordIndex().removeByPrefix(kbPrefix + fileName + "_");
        hybridSearchService.getKeywordIndex().removeByPrefix(fileName + "_");

        documentHashService.deleteByFileName(doc.getId());
    }

    /**
     * <h3>删除本地文件（best-effort）</h3>
     *
     * <p>仅处理本地路径（非 http(s)），文件不存在时不报错。
     * 删除失败仅记录 warn 日志，不阻塞主流程。</p>
     *
     * @param filePath 文件路径（可为 null）
     * @param label    日志标签（如"原始文件"/"解析文件"）
     */
    private void deleteFileIfLocal(String filePath, String label) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            log.debug("{}为远端资源，跳过删除: {}", label, filePath);
            return;
        }
        try {
            Path path = Paths.get(filePath);
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                log.info("{}已删除: {}", label, path);
            } else {
                log.debug("{}不存在，跳过: {}", label, path);
            }
        } catch (Exception e) {
            log.warn("{}删除失败: path={}, error={}", label, filePath, e.getMessage());
        }
    }

    /**
     * <h3>删除知识库下的所有文件</h3>
     *
     * <p>知识库删除时级联清理关联文件。</p>
     *
     * @param kbId 知识库 ID
     */
    @Transactional
    public void deleteByKbId(Long kbId) {
        log.info("级联删除知识库文件: kbId={}", kbId);

        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKbId, kbId);
        List<KnowledgeDocument> docs = knowledgeDocumentMapper.selectList(wrapper);
        for (KnowledgeDocument doc : docs) {
            cleanupDocumentResources(doc);
        }
        knowledgeDocumentMapper.delete(wrapper);

        log.info("知识库文件已全部逻辑删除: kbId={}", kbId);
    }

    // ======================== 文档解析内容预览 ========================

    /**
     * <h3>读取解析后的文档内容</h3>
     *
     * <p>读取文档已解析的 Markdown 原文，供前端"预览"渲染。
     * 纯本地文件读取，同步返回。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>查询文档 → 404 "文件不存在"</li>
     *   <li>校验 parsedTextUrl 非空 → 400 "请先完成文档解析"</li>
     *   <li>校验 status=2（解析完成）→ 400 "文档未完成解析，无法预览"</li>
     *   <li>校验解析文件存在 → 400 "解析文件已丢失，请重新解析"</li>
     *   <li>读取 Markdown 文本 → 返回内容</li>
     * </ol>
     *
     * @param documentId 文档 ID
     * @return 解析内容（含文档 ID、文件名、Markdown 原文）
     * @throws BusinessException 文档未完成解析或解析文件丢失时抛出
     */
    public KnowledgeDocumentContentVO getParsedContent(Long documentId) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(404, "文件不存在");
        }
        checkDocumentPermission(doc, Role.VIEWER);

        String parsedTextUrl = doc.getParsedTextUrl();
        if (parsedTextUrl == null || parsedTextUrl.isBlank()) {
            throw new BusinessException(400, "请先完成文档解析");
        }

        if (!Integer.valueOf(2).equals(doc.getStatus())) {
            throw new BusinessException(400, "文档未完成解析，无法预览");
        }

        Path mdPath = Paths.get(parsedTextUrl);
        if (!Files.exists(mdPath)) {
            throw new BusinessException(400, "解析文件已丢失，请重新解析");
        }

        String markdown;
        try {
            markdown = Files.readString(mdPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取解析文件失败: path={}", mdPath, e);
            throw new BusinessException(500, "读取解析文件失败");
        }

        log.info("读取解析内容: documentId={}, fileName={}, chars={}",
                documentId, doc.getFileName(), markdown.length());

        return new KnowledgeDocumentContentVO(documentId, doc.getFileName(), markdown);
    }

    /**
     * <h3>保存编辑后的解析内容</h3>
     *
     * <p>将前端编辑后的 Markdown 原文写回解析文件，并清空该文档已有的切片，
     * 因为内容变更后旧切片已失效，需用户重新切片/向量化。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>查询文档 → 404 "文件不存在"</li>
     *   <li>校验未向量化（isVectorized != 2）→ 400 "文档已向量化，无法编辑"</li>
     *   <li>校验 parsedTextUrl 非空 → 400 "请先完成文档解析"</li>
     *   <li>校验 status=2 → 400 "文档未完成解析，无法编辑"</li>
     *   <li>校验 content 非空 → 400 "内容不能为空"</li>
     *   <li>校验解析文件存在 → 400 "解析文件已丢失，请重新解析"</li>
     *   <li>若存在已保存切片且 confirm 不为 true → 400 提示确认</li>
     *   <li>写回 Markdown 文件 + 删除切片 + 重置 chunkCount/tokenCount/isVectorized</li>
     * </ol>
     *
     * <h4>设计考量</h4>
     * <ul>
     *   <li><b>confirm 确认机制</b>：存在切片时首次提交返回提示，前端弹框后带 confirm=true 重试。</li>
     *   <li><b>事务保护</b>：写文件、删切片、更新文档字段在同一事务内，写文件失败整体回滚。</li>
     * </ul>
     *
     * @param documentId 文档 ID
     * @param request    编辑请求（含 content、confirm）
     * @throws BusinessException 校验未通过或写文件失败时抛出
     */
    @Transactional
    public void updateParsedContent(Long documentId, KnowledgeDocumentContentUpdateDTO request) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(404, "文件不存在");
        }
        checkDocumentPermission(doc, Role.EDITOR);

        if (Integer.valueOf(2).equals(doc.getIsVectorized())) {
            throw new BusinessException(400, "文档已向量化，无法编辑");
        }

        String parsedTextUrl = doc.getParsedTextUrl();
        if (parsedTextUrl == null || parsedTextUrl.isBlank()) {
            throw new BusinessException(400, "请先完成文档解析");
        }

        if (!Integer.valueOf(2).equals(doc.getStatus())) {
            throw new BusinessException(400, "文档未完成解析，无法编辑");
        }

        if (request == null || request.getContent() == null || request.getContent().isBlank()) {
            throw new BusinessException(400, "内容不能为空");
        }

        Path mdPath = Paths.get(parsedTextUrl);
        if (!Files.exists(mdPath)) {
            throw new BusinessException(400, "解析文件已丢失，请重新解析");
        }

        // ===== 存在切片时要求用户确认 =====
        LambdaQueryWrapper<KnowledgeDocumentChunk> chunkQuery = new LambdaQueryWrapper<>();
        chunkQuery.eq(KnowledgeDocumentChunk::getDocumentId, documentId);
        Long chunkCount = knowledgeDocumentChunkMapper.selectCount(chunkQuery);
        boolean hasChunks = chunkCount != null && chunkCount > 0;
        if (hasChunks && !Boolean.TRUE.equals(request.getConfirm())) {
            throw new BusinessException(400, "文档已有切片，保存后将清空切片并需重新向量化");
        }

        // ===== 1. 写回 Markdown 文件 =====
        try {
            Files.writeString(mdPath, request.getContent(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("保存解析内容失败: documentId={}, path={}", documentId, mdPath, e);
            throw new BusinessException(500, "保存解析内容失败");
        }

        // ===== 2. 清空已有切片 =====
        if (hasChunks) {
            LambdaQueryWrapper<KnowledgeDocumentChunk> deleteWrapper = new LambdaQueryWrapper<>();
            deleteWrapper.eq(KnowledgeDocumentChunk::getDocumentId, documentId);
            knowledgeDocumentChunkMapper.delete(deleteWrapper);
        }

        // ===== 3. 重置文档统计与向量化状态 =====
        doc.setChunkCount(0);
        doc.setTokenCount(0);
        doc.setIsVectorized(0);
        knowledgeDocumentMapper.updateById(doc);

        log.info("解析内容保存完成: documentId={}, fileName={}, chars={}",
                documentId, doc.getFileName(), request.getContent().length());
    }

    // ======================== 文档切片预览 ========================

    /**
     * <h3>文档切片预览（同步）</h3>
     *
     * <p>优先从 {@code knowledge_document_chunk} 表返回已保存的切片（前端审校后的最终版本），
     * 若未保存过则读取已解析的 Markdown 文件自动切片。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>查询文档 → 404</li>
     *   <li>查询 knowledge_document_chunk → 有已保存切片直接返回</li>
     *   <li>校验 parsedTextUrl 非空 → 400 "请先完成文档解析"</li>
     *   <li>校验 status=2（解析完成）→ 400 "文档未完成解析，无法切片"</li>
     *   <li>读取 parsedTextUrl 中 Markdown 文本 → 400 "解析文件已丢失"</li>
     *   <li>按知识库切片策略切片（{@code AUTO} 走内容路由，其余走通用切分器）</li>
     *   <li>转换 Chunk → {@link KnowledgeDocumentChunksVO.ChunkItem} 列表</li>
     * </ol>
     *
     * <h4>性能说明</h4>
     * <p>已保存切片走 DB 查询（&lt; 5ms），未保存时自动切片走纯本地 CPU 操作（&lt; 2 秒）。</p>
     *
     * @param documentId 文档 ID
     * @return 切片预览结果（含文件名、切片总数、片段列表）
     * @throws BusinessException 文档未完成解析或解析文件丢失时抛出
     */
    public KnowledgeDocumentChunksVO chunkDocument(Long documentId) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(404, "文件不存在");
        }
        checkDocumentPermission(doc, Role.VIEWER);

        LambdaQueryWrapper<KnowledgeDocumentChunk> chunkQuery = new LambdaQueryWrapper<>();
        chunkQuery.eq(KnowledgeDocumentChunk::getDocumentId, documentId)
                   .orderByAsc(KnowledgeDocumentChunk::getChunkIndex);
        List<KnowledgeDocumentChunk> savedChunks = knowledgeDocumentChunkMapper.selectList(chunkQuery);
        if (!savedChunks.isEmpty()) {
            log.info("返回已保存切片: documentId={}, count={}", documentId, savedChunks.size());
            return buildChunksResponse(doc, savedChunks, savedChunks.size());
        }

        String parsedTextUrl = doc.getParsedTextUrl();
        if (parsedTextUrl == null || parsedTextUrl.isBlank()) {
            throw new BusinessException(400, "请先完成文档解析");
        }

        if (!Integer.valueOf(2).equals(doc.getStatus())) {
            throw new BusinessException(400, "文档未完成解析，无法切片");
        }

        Path mdPath = Paths.get(parsedTextUrl);
        if (!Files.exists(mdPath)) {
            throw new BusinessException(400, "解析文件已丢失，请重新解析");
        }

        String markdown;
        try {
            long fileSizeBytes = Files.size(mdPath);
            long maxBytes = maxChunkFileSizeMb * 1024L * 1024L;
            if (fileSizeBytes > maxBytes) {
                throw new BusinessException(400,
                        "解析文件过大（" + (fileSizeBytes / 1024 / 1024) + "MB），" +
                        "最大支持 " + maxChunkFileSizeMb + "MB，请减小文档体积后重新上传");
            }

            markdown = Files.readString(mdPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取解析文件失败: path={}", mdPath, e);
            throw new BusinessException(500, "读取解析文件失败");
        }

        log.info("开始切片: documentId={}, fileName={}, chars={}",
                documentId, doc.getFileName(), markdown.length());

        List<MarkdownDocumentChunker.Chunk> chunks = chunkWithStrategy(doc, markdown);
        log.info("切片完成: documentId={}, totalChunks={}",
                documentId, chunks.size());

        List<KnowledgeDocumentChunksVO.ChunkItem> items = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            MarkdownDocumentChunker.Chunk chunk = chunks.get(i);
            KnowledgeDocumentChunksVO.ChunkItem item = new KnowledgeDocumentChunksVO.ChunkItem();
            item.setIndex(i);
            item.setText(chunk.text());
            item.setTokenCount(chunk.tokenCount());
            item.setEnabled(true);
            item.setContextPrefix(chunk.contextPrefix());
            item.setMetadata(new java.util.HashMap<>(chunk.metadata()));
            items.add(item);
        }

        KnowledgeDocumentChunksVO vo = new KnowledgeDocumentChunksVO();
        vo.setDocumentId(documentId);
        vo.setFileName(doc.getFileName());
        vo.setTotalChunks(chunks.size());
        vo.setChunks(items);

        return vo;
    }

    /**
     * <h3>按知识库切片参数对文档内容切片</h3>
     *
     * <p>读取文档所属知识库的切片参数（策略 / 切片长度 / 重叠长度），构建
     * {@link CustomDocument} 后交给 {@code documentChunkerService.chunkByStrategy}
     * 分派，保证预览、快速切片、正式向量化三处切片结果一致。</p>
     *
     * @param doc      目标文档实体（提供文件名/格式/所属知识库）
     * @param markdown 已解析的文档文本
     * @return 切分后的 Chunk 列表
     */
    private List<MarkdownDocumentChunker.Chunk> chunkWithStrategy(KnowledgeDocument doc, String markdown) {
        ChunkingParams params = knowledgeBaseService.getChunkingParams(doc.getKbId());
        CustomDocument customDocument = CustomDocument.builder()
                .fileName(doc.getFileName())
                .format(CustomDocument.Format.fromString(doc.getFileExt()))
                .content(markdown)
                .build();
        return documentChunkerService.chunkByStrategy(customDocument, params.getSplitStrategy(),
                params.getChunkLength(), params.getOverlapLength());
    }

    /**
     * <h3>从已保存的切片实体构建响应</h3>
     *
     * <p>将 {@link KnowledgeDocumentChunk} 实体列表还原为前端可展示的
     * {@link KnowledgeDocumentChunksVO.ChunkItem} 列表。</p>
     *
     * <p>metadata 字段从实体的扁平字段反序列化回 Map 结构，
     * 保持与自动切片时返回的格式一致。</p>
     */
    private KnowledgeDocumentChunksVO buildChunksResponse(KnowledgeDocument doc,
                                                           List<KnowledgeDocumentChunk> chunks,
                                                           int total) {
        List<KnowledgeDocumentChunksVO.ChunkItem> items = new java.util.ArrayList<>(chunks.size());
        for (KnowledgeDocumentChunk chunk : chunks) {
            items.add(toChunkItem(chunk));
        }

        KnowledgeDocumentChunksVO vo = new KnowledgeDocumentChunksVO();
        vo.setDocumentId(doc.getId());
        vo.setFileName(doc.getFileName());
        vo.setTotalChunks(total);
        vo.setChunks(items);
        return vo;
    }

    /**
     * <h3>将片段实体转换为响应项</h3>
     *
     * <p>包含数据库主键、序号、文本、token 数、启用状态以及元数据，
     * 供预览与片段管理接口复用。</p>
     */
    private KnowledgeDocumentChunksVO.ChunkItem toChunkItem(KnowledgeDocumentChunk chunk) {
        KnowledgeDocumentChunksVO.ChunkItem item = new KnowledgeDocumentChunksVO.ChunkItem();
        item.setId(chunk.getId());
        item.setIndex(chunk.getChunkIndex() != null ? chunk.getChunkIndex() : 0);
        item.setText(chunk.getText());
        item.setTokenCount(chunk.getTokenCount() != null ? chunk.getTokenCount() : 0);
        item.setEnabled(chunk.getEnabled() == null || chunk.getEnabled());
        item.setContextPrefix(chunk.getContextPrefix());

        Map<String, String> meta = new java.util.HashMap<>();
        if (chunk.getContentType() != null) {
            meta.put("content_type", chunk.getContentType());
        }
        if (chunk.getParentText() != null) {
            meta.put("parent_text", chunk.getParentText());
        }
        if (chunk.getParentStart() != null) {
            meta.put("parent_start", String.valueOf(chunk.getParentStart()));
        }
        if (chunk.getParentEnd() != null) {
            meta.put("parent_end", String.valueOf(chunk.getParentEnd()));
        }
        item.setMetadata(meta);
        return item;
    }

    /**
     * <h3>保存审校后的切片（全量覆盖）</h3>
     *
     * <p>前端在切片预览后可以增删改切片，确认无误后调用此接口全量写入。
     * 先删除该文档的旧切片，再按数组顺序重新插入，保证与前端展示一致。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>查询文档 → 404</li>
     *   <li>校验 status=2（解析完成）→ 400 "文档未完成解析，无法保存切片"</li>
     *   <li>校验切片列表非空 → 400 "切片列表不能为空"</li>
     *   <li>已向量化（isVectorized == 2）且未确认 → 400 "文档已向量化，重新保存切片将清除向量化数据，需重新向量化"</li>
     *   <li>清理该文档向量化数据（PGVector + BM25 索引）</li>
     *   <li>删除该文档下所有旧切片（先删后插 = 全量覆盖）</li>
     *   <li>按数组顺序插入新切片，chunkIndex 从 0 开始递增</li>
     *   <li>更新 knowledge_document.chunkCount / tokenCount / isVectorized=0</li>
     * </ol>
     *
     * <h4>设计考量</h4>
     * <ul>
     *   <li><b>全量覆盖而非增量更新</b>：前端审校后切片可能完全不同（合并、拆分、新增、删除），
     *       增量比较逻辑复杂且易出错，全量覆盖更可靠。</li>
     *   <li><b>重新向量化入口</b>：保存切片会无条件清理向量化数据并重置 isVectorized=0，
     *       后续用户可重新触发向量化。</li>
     *   <li><b>事务保护</b>：{@code @Transactional} 确保删除 + 批量插入 + 更新文档计数字段
     *       在同一事务中原子完成。</li>
     * </ul>
     *
     * @param documentId 文档 ID
     * @param chunks     前端审校后的切片列表
     * @param confirm    用户确认标记（已向量化文档重新保存需为 true）
     * @throws BusinessException 文档状态异常或切片为空时抛出
     */
    @Transactional
    public void saveChunks(Long documentId, List<KnowledgeDocumentChunksVO.ChunkItem> chunks, boolean confirm) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(404, "文件不存在");
        }
        checkDocumentPermission(doc, Role.EDITOR);

        if (!Integer.valueOf(2).equals(doc.getStatus())) {
            throw new BusinessException(400, "文档未完成解析，无法保存切片");
        }

        if (chunks == null || chunks.isEmpty()) {
            throw new BusinessException(400, "切片列表不能为空");
        }

        if (Integer.valueOf(2).equals(doc.getIsVectorized()) && !confirm) {
            throw new BusinessException(400, "文档已向量化，重新保存切片将清除向量化数据，需重新向量化");
        }

        log.info("开始保存切片: documentId={}, chunkCount={}, confirm={}", documentId, chunks.size(), confirm);

        // 清理该文档向量化数据（PGVector + BM25 索引），避免与新切片不一致
        String fileName = doc.getFileName() != null ? doc.getFileName() : "document";
        jdbcTemplate.update(
                "DELETE FROM " + vectorTableName + " WHERE metadata->>'document_id' = ?",
                String.valueOf(documentId));
        log.debug("保存切片: 清理旧向量完成, id={}, table={}", documentId, vectorTableName);

        com.hai.aiknowledgebase.common.KeywordIndex keywordIndex = hybridSearchService.getKeywordIndex();
        keywordIndex.removeByPrefix(fileName + "_");
        log.debug("保存切片: 清理旧 BM25 索引完成, prefix={}_", fileName);

        LambdaQueryWrapper<KnowledgeDocumentChunk> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(KnowledgeDocumentChunk::getDocumentId, documentId);
        knowledgeDocumentChunkMapper.delete(deleteWrapper);

        int totalTokens = 0;
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeDocumentChunksVO.ChunkItem item = chunks.get(i);
            KnowledgeDocumentChunk chunk = new KnowledgeDocumentChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setText(item.getText());
            chunk.setTokenCount(item.getTokenCount());
            chunk.setContextPrefix(item.getContextPrefix());
            chunk.setEnabled(item.getEnabled() == null || item.getEnabled());

            Map<String, String> meta = item.getMetadata();
            if (meta != null) {
                chunk.setContentType(meta.get("content_type"));
                chunk.setParentText(meta.get("parent_text"));
                String ps = meta.get("parent_start");
                if (ps != null) {
                    chunk.setParentStart(Integer.parseInt(ps));
                }
                String pe = meta.get("parent_end");
                if (pe != null) {
                    chunk.setParentEnd(Integer.parseInt(pe));
                }
            }

            knowledgeDocumentChunkMapper.insert(chunk);
            totalTokens += item.getTokenCount();
        }

        doc.setChunkCount(chunks.size());
        doc.setTokenCount(totalTokens);
        doc.setIsVectorized(0);
        knowledgeDocumentMapper.updateById(doc);

        log.info("切片保存完成: documentId={}, chunkCount={}, totalTokens={}",
                documentId, chunks.size(), totalTokens);
    }

    // ======================== 快速切片 API ========================

    /**
     * <h3>快速切片并保存（单文档）</h3>
     *
     * <p>跳过人工审校步骤，直接对已解析的 Markdown 文件执行自动切片
     * 并全量覆盖写入 {@code knowledge_document_chunk} 表。</p>
     *
     * <p>适用场景：批量入库大量文档时，用户无暇逐一审校切片质量，
     * 通过此接口快速完成切片入库，后续可直接并行触发向量化。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>查文档 + 状态校验</li>
     *   <li>读取 Markdown + 自动切片</li>
     *   <li>先删后插（全量覆盖）写入 knowledge_document_chunk</li>
     *   <li>更新 chunkCount / tokenCount / isVectorized=false</li>
     * </ol>
     *
     * <h4>校验矩阵</h4>
     * <table>
     *   <tr><td>条件</td><td>HTTP</td><td>message</td></tr>
     *   <tr><td>文档不存在</td><td>404</td><td>文件不存在</td></tr>
     *   <tr><td>已向量化</td><td>400</td><td>文档已向量化，无法快速切片</td></tr>
     *   <tr><td>status != 2</td><td>400</td><td>文档未完成解析，无法切片</td></tr>
     *   <tr><td>parsedTextUrl 为空</td><td>400</td><td>请先完成文档解析</td></tr>
     *   <tr><td>解析文件不存在</td><td>400</td><td>解析文件已丢失，请重新解析</td></tr>
     * </table>
     *
     * @param documentId 文档 ID
     * @throws BusinessException 任一校验未通过时抛出
     */
    @Transactional
    public void autoChunkAndSave(Long documentId) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(404, "文件不存在");
        }
        checkDocumentPermission(doc, Role.EDITOR);

        if (Integer.valueOf(2).equals(doc.getIsVectorized())) {
            throw new BusinessException(400, "文档已向量化，无法快速切片");
        }

        if (!Integer.valueOf(2).equals(doc.getStatus())) {
            throw new BusinessException(400, "文档未完成解析，无法切片");
        }

        String parsedTextUrl = doc.getParsedTextUrl();
        if (parsedTextUrl == null || parsedTextUrl.isBlank()) {
            throw new BusinessException(400, "请先完成文档解析");
        }

        Path mdPath = Paths.get(parsedTextUrl);
        if (!Files.exists(mdPath)) {
            throw new BusinessException(400, "解析文件已丢失，请重新解析");
        }

        String markdown;
        try {
            long fileSizeBytes = Files.size(mdPath);
            long maxBytes = maxChunkFileSizeMb * 1024L * 1024L;
            if (fileSizeBytes > maxBytes) {
                throw new BusinessException(400,
                        "解析文件过大（" + (fileSizeBytes / 1024 / 1024) + "MB），" +
                        "最大支持 " + maxChunkFileSizeMb + "MB，请减小文档体积后重新上传");
            }
            markdown = Files.readString(mdPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取解析文件失败: documentId={}, path={}", documentId, mdPath, e);
            throw new BusinessException(500, "读取解析文件失败");
        }

        log.info("快速切片: documentId={}, fileName={}, chars={}",
                documentId, doc.getFileName(), markdown.length());

        List<MarkdownDocumentChunker.Chunk> chunks = chunkWithStrategy(doc, markdown);

        LambdaQueryWrapper<KnowledgeDocumentChunk> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(KnowledgeDocumentChunk::getDocumentId, documentId);
        knowledgeDocumentChunkMapper.delete(deleteWrapper);

        int totalTokens = 0;
        for (int i = 0; i < chunks.size(); i++) {
            MarkdownDocumentChunker.Chunk c = chunks.get(i);
            KnowledgeDocumentChunk chunk = new KnowledgeDocumentChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setText(c.text());
            chunk.setTokenCount(c.tokenCount());
            chunk.setContextPrefix(c.contextPrefix());
            chunk.setEnabled(true);

            Map<String, String> meta = c.metadata();
            if (meta != null) {
                chunk.setContentType(meta.get("content_type"));
                chunk.setParentText(meta.get("parent_text"));
                String ps = meta.get("parent_start");
                if (ps != null) chunk.setParentStart(Integer.parseInt(ps));
                String pe = meta.get("parent_end");
                if (pe != null) chunk.setParentEnd(Integer.parseInt(pe));
            }

            knowledgeDocumentChunkMapper.insert(chunk);
            totalTokens += c.tokenCount();
        }

        doc.setChunkCount(chunks.size());
        doc.setTokenCount(totalTokens);
        doc.setIsVectorized(0);
        knowledgeDocumentMapper.updateById(doc);

        log.info("快速切片完成: documentId={}, fileName={}, chunkCount={}, totalTokens={}",
                documentId, doc.getFileName(), chunks.size(), totalTokens);
    }

    /**
     * <h3>批量快速切片并保存</h3>
     *
     * <p>遍历文档 ID 列表，逐个调用 {@link #autoChunkAndSave}。单个文档失败
     * 只记录失败明细，不中断其他文档的处理（best-effort）。</p>
     *
     * <h4>预检规则</h4>
     * <p>开始处理前预检，只要列表中存在任意已向量化（isVectorized == 2）的文档，
     * 整个批次直接拒绝，一个都不处理。</p>
     *
     * @param documentIds 文档 ID 列表
     * @return 批量处理结果（总数、成功数、失败明细）
     * @throws BusinessException 列表中存在已向量化文档时抛出
     */
    public BatchChunkResult autoChunkBatch(List<Long> documentIds) {
        // ===== 预检：权限 + 已向量化文档则整体拒绝 =====
        for (Long id : documentIds) {
            KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
            if (doc != null) {
                checkDocumentPermission(doc, Role.EDITOR);
                if (Integer.valueOf(2).equals(doc.getIsVectorized())) {
                    log.warn("批量快速切片被拒绝: 存在已向量化文档, id={}", id);
                    throw new BusinessException(400, "存在已向量化的文档，无法快速切片");
                }
            }
        }

        int total = documentIds.size();
        List<BatchChunkResult.Failure> failures = new java.util.ArrayList<>();

        for (Long id : documentIds) {
            try {
                self.autoChunkAndSave(id);
            } catch (BusinessException e) {
                log.warn("批量快速切片失败: id={}, message={}", id, e.getMessage());
                failures.add(new BatchChunkResult.Failure(id, e.getMessage()));
            } catch (Exception e) {
                log.error("批量快速切片异常: id={}", id, e);
                failures.add(new BatchChunkResult.Failure(id, "内部错误"));
            }
        }

        int success = total - failures.size();
        log.info("批量快速切片完成: total={}, success={}, failed={}", total, success, failures.size());
        return new BatchChunkResult(total, success, failures);
    }

    // ======================== 文档向量化 API ========================

    /**
     * <h3>启动文档向量化（同步入口）</h3>
     *
     * <p>校验文档状态和切片后立即将状态更新为 1（处理中）并返回，
     * 实际向量化任务通过 {@link #vectorizeAsync} 在 {@code vectorizationTaskExecutor}
     * 线程池中异步执行。</p>
     *
     * <p>设计原因：向量化涉及 ONNX 嵌入模型推理和 PGVector 批量写入，
     * 是 CPU 密集型耗时操作，不在 HTTP 主线程上阻塞，也不持有数据库事务。</p>
     *
     * <h4>前置校验</h4>
     * <table>
     *   <tr><td>文档不存在</td><td>404</td><td>"文件不存在"</td></tr>
     *   <tr><td>status != 2</td><td>400</td><td>"文档未完成解析，无法向量化"</td></tr>
     *   <tr><td>chunkCount 为空或0</td><td>400</td><td>"请先保存切片后再向量化"</td></tr>
     *   <tr><td>status == 1</td><td>400</td><td>"文档正在处理中，请稍后再试"</td></tr>
     * </table>
     *
     * <p><b>允许重复向量化</b>：用户重新保存切片后 isVectorized 已被置为 false，
     * 可再次触发向量化。旧 PGVector 数据后续可通过清理任务移除。</p>
     *
     * @param documentId 文档 ID
     * @return 文档 VO（status=1，前端轮询等待完成）
     */
    public KnowledgeDocumentVO startVectorize(Long documentId) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(404, "文件不存在");
        }
        checkDocumentPermission(doc, Role.EDITOR);

        if (!Integer.valueOf(2).equals(doc.getStatus())) {
            throw new BusinessException(400, "文档未完成解析，无法向量化");
        }

        if (Integer.valueOf(2).equals(doc.getIsVectorized())) {
            throw new BusinessException(400, "文档已向量化，如需重新向量化请先重新保存切片");
        }

        if (Integer.valueOf(1).equals(doc.getIsVectorized())) {
            throw new BusinessException(400, "向量化任务正在进行中，请稍后再试");
        }

        if (doc.getChunkCount() == null || doc.getChunkCount() == 0) {
            throw new BusinessException(400, "请先保存切片后再向量化");
        }

        log.info("启动向量化: id={}, fileName={}, chunkCount={}",
                documentId, doc.getFileName(), doc.getChunkCount());

        doc.setIsVectorized(1);
        doc.setErrorMessage(null);
        knowledgeDocumentMapper.updateById(doc);

        self.vectorizeAsync(documentId);

        return toVO(doc);
    }

    /**
     * <h3>异步执行文档向量化</h3>
     *
     * <p>从 {@code knowledge_document_chunk} 表读取用户审校后的切片，
     * 嵌入向量化后存入 PGVector 和 BM25 索引。整个过程在独立线程中执行，
     * 不与 HTTP 请求共享生命周期。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>从 {@code knowledge_document_chunk} 读取切片（按 chunk_index 排序）</li>
     *   <li>构建 LangChain4j {@link TextSegment} 列表（含 source/fileName/chunk_index 元数据）</li>
     *   <li>遍历 → {@link EmbeddingModel#embed} → {@link EmbeddingStore#add} 存入 PGVector</li>
     *   <li>遍历 → BM25 关键词索引（chunkId = kbId_fileName_chunkIndex）</li>
     *   <li>保存文本级 SHA-256 哈希（去重依据）</li>
     *   <li>更新 status=2 + isVectorized=true</li>
     * </ol>
     *
     * <h4>异常处理</h4>
     *
     * <p>任何步骤失败都会将 isVectorized 置为 3（失败）并记录 errorMessage，status 保持不动</p>
     *
     * @param documentId 文档 ID
     */
    @Async("vectorizationTaskExecutor")
    public void vectorizeAsync(Long documentId) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("向量化失败: 文档不存在, id={}", documentId);
            return;
        }

        try {
            LambdaQueryWrapper<KnowledgeDocumentChunk> chunkQuery = new LambdaQueryWrapper<>();
            chunkQuery.eq(KnowledgeDocumentChunk::getDocumentId, documentId)
                    .orderByAsc(KnowledgeDocumentChunk::getChunkIndex);
            List<KnowledgeDocumentChunk> chunks = knowledgeDocumentChunkMapper.selectList(chunkQuery);

            if (chunks == null || chunks.isEmpty()) {
                log.warn("向量化失败: 切片为空, id={}", documentId);
                failVectorize(doc, "切片数据为空，请重新保存切片");
                return;
            }

            log.info("向量化: 读取切片完成, id={}, count={}", documentId, chunks.size());

            String fileName = doc.getFileName() != null ? doc.getFileName() : "document";
            // 知识库隔离：chunkId 前缀格式为 "kbId_fileName"，用于 BM25 检索时的前缀过滤
            String kbPrefix = doc.getKbId() != null ? doc.getKbId() + "_" : "";

            List<TextSegment> segments = new java.util.ArrayList<>(chunks.size());
            StringBuilder allText = new StringBuilder();
            for (KnowledgeDocumentChunk chunk : chunks) {
                Metadata metadata = buildChunkMetadata(doc, chunk);
                String text = chunk.getText() != null ? chunk.getText() : "";
                segments.add(TextSegment.from(text, metadata));
                allText.append(text).append("\n");
            }

            // 清理旧向量/索引数据（避免重复向量化后 PGVector 和 BM25 残留）
            jdbcTemplate.update(
                    "DELETE FROM " + vectorTableName + " WHERE metadata->>'document_id' = ?",
                    String.valueOf(documentId));
            log.debug("向量化: 清理旧向量完成, id={}, table={}", documentId, vectorTableName);

            com.hai.aiknowledgebase.common.KeywordIndex keywordIndex =
                    hybridSearchService.getKeywordIndex();
            // 清理旧 BM25 索引：先按新格式 "kbId_fileName_" 清理，再兼容旧格式 "fileName_"
            keywordIndex.removeByPrefix(kbPrefix + fileName + "_");
            keywordIndex.removeByPrefix(fileName + "_");
            log.debug("向量化: 清理旧 BM25 索引完成, prefix={}{}_", kbPrefix, fileName);

            for (TextSegment segment : segments) {
                Embedding embedding = embeddingModel.embed(segment).content();
                embeddingStore.add(embedding, segment);
            }
            log.info("向量化: PGVector 存储完成, id={}, segments={}", documentId, segments.size());

            int bm25Count = 0;
            for (KnowledgeDocumentChunk chunk : chunks) {
                // 禁用片段不进入 BM25 索引（向量仍保留，检索时由向量路径过滤）
                if (chunk.getEnabled() != null && !chunk.getEnabled()) {
                    continue;
                }
                // chunkId 使用数据库主键（编辑/合并/拆分后保持稳定），格式 "kbId_fileName_chunkId"
                String chunkId = kbPrefix + fileName + "_" + chunk.getId();
                keywordIndex.index(chunkId, chunk.getText() != null ? chunk.getText() : "");
                bm25Count++;
            }
            log.info("向量化: BM25 索引完成, id={}, chunks={}", documentId, bm25Count);

            String contentHash = DigestUtils.sha256Hex(allText.toString());
            documentHashService.save(contentHash, HashType.HASH_TYPE_TEXT,
                    fileName, documentId);

            doc.setIsVectorized(2);
            doc.setErrorMessage(null);
            knowledgeDocumentMapper.updateById(doc);

            log.info("向量化完成: id={}, fileName={}, chunks={}",
                    documentId, fileName, segments.size());

        } catch (Exception e) {
            log.error("向量化失败: id={}", documentId, e);
            failVectorize(doc, "向量化失败: " + e.getMessage());
        }
    }

    /**
     * 向量化失败：isVectorized 置为 3（失败）并记录错误信息，status 保持不动
     */
    private void failVectorize(KnowledgeDocument doc, String errorMessage) {
        doc.setIsVectorized(3);
        doc.setErrorMessage(errorMessage);
        knowledgeDocumentMapper.updateById(doc);
    }

    // ======================== 文档解析 API ========================

    /**
     * <h3>启动文档解析（同步入口）</h3>
     *
     * <p>校验文档存在性后立即将状态更新为 1（处理中）并返回，
     * 实际解析任务通过 {@link #parseDocumentAsync} 异步执行。</p>
     *
     * <p>设计原因：解析涉及 Python 子进程调用和远程 HTTP 下载，是耗时操作，
     * 不在主线程上阻塞，也不应持有数据库事务。</p>
     *
     * @param documentId 文档 ID
     * @return 文档 VO（status=1，前端可据此展示"解析中"状态）
     */
    public KnowledgeDocumentVO startParse(Long documentId) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在");
        }
        checkDocumentPermission(doc, Role.EDITOR);

        String fileUrl = doc.getFileUrl();
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new BusinessException(400, "文档原始文件路径为空，无法解析");
        }

        if (Integer.valueOf(2).equals(doc.getIsVectorized())) {
            throw new BusinessException(400, "文档已向量化，如需重新解析请先重新保存切片");
        }

        if (Integer.valueOf(1).equals(doc.getIsVectorized())) {
            throw new BusinessException(400, "向量化任务正在进行中，请稍后再试");
        }

        log.info("启动文档解析: id={}, fileUrl={}", documentId, fileUrl);

        // 立即更新状态为"处理中"，让前端轮询时看到变化
        doc.setStatus(1);
        doc.setErrorMessage(null);
        knowledgeDocumentMapper.updateById(doc);

        self.parseDocumentAsync(documentId);

        return toVO(doc);
    }

    /**
     * <h3>异步解析文档为 Markdown</h3>
     *
     * <p>使用 {@code parseTaskExecutor} 线程池异步执行，不阻塞 HTTP 响应。
     * 解析成功/失败均通过 {@code knowledgeDocumentMapper.updateById} 写回数据库，
     * 前端通过轮询列表或详情接口获取最新状态。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>查询文档记录 + 获取 {@code file_url}</li>
     *   <li>下载/读取原始文件（本地路径 or 远程 URL → 临时文件）</li>
     *   <li>调用 {@link DocumentParserService#parseDocument} 解析为 Markdown</li>
     *   <li>Markdown 写入 {@code parse/{documentId}.md}</li>
     *   <li>清理旧切片（重新解析后旧切片已失效）</li>
     *   <li>更新 {@code parsed_text_url} + status=2（已完成）</li>
     * </ol>
     *
     * @param documentId 文档 ID
     */
    @Async("parseTaskExecutor")
    public void parseDocumentAsync(Long documentId) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("异步解析：文档不存在: id={}", documentId);
            return;
        }

        String fileUrl = doc.getFileUrl();
        String fileName = doc.getFileName();
        Path sourcePath = null;
        boolean needCleanup = false;

        try {
            // ===== 1. 获取原始文件 =====
            if (fileUrl.startsWith("http://") || fileUrl.startsWith("https://")) {
                sourcePath = downloadToTemp(fileUrl, fileName);
                needCleanup = true;
            } else {
                sourcePath = Paths.get(fileUrl);
                if (!Files.exists(sourcePath)) {
                    log.error("原始文件不存在: {}", sourcePath);
                    handleParseFailure(doc, "原始文件不存在: " + fileUrl);
                    return;
                }
            }

            // ===== 2. 调用解析引擎 =====
            CustomDocument customDoc = documentParserService.parseDocument(
                    sourcePath, fileName);
            String markdownContent = customDoc.getContent();
            log.info("文档解析成功: id={}, contentLength={}", documentId, markdownContent.length());

            // ===== 3. 保存 Markdown 到 parse 目录 =====
            Path parseDir = Paths.get(parseOutputDir);
            if (!Files.exists(parseDir)) {
                Files.createDirectories(parseDir);
            }
            Path mdFilePath = parseDir.resolve(documentId + ".md");
            Files.writeString(mdFilePath, markdownContent, StandardCharsets.UTF_8);
            log.info("Markdown 已保存: {}", mdFilePath);

            // ===== 4. 清理旧切片（重新解析后旧切片已失效） =====
            LambdaQueryWrapper<KnowledgeDocumentChunk> chunkDeleteWrapper = new LambdaQueryWrapper<>();
            chunkDeleteWrapper.eq(KnowledgeDocumentChunk::getDocumentId, documentId);
            knowledgeDocumentChunkMapper.delete(chunkDeleteWrapper);
            doc.setChunkCount(0);
            doc.setTokenCount(0);

            // ===== 5. 更新数据库 —— 解析成功 =====
            doc.setParsedTextUrl(mdFilePath.toString());
            doc.setStatus(2);
            doc.setErrorMessage(null);
            knowledgeDocumentMapper.updateById(doc);

            log.info("文档解析完成: id={}, parsedTextUrl={}", documentId, mdFilePath);

        } catch (BusinessException e) {
            log.error("文档解析业务异常: id={}", documentId, e);
            handleParseFailure(doc, e.getMessage());
        } catch (Exception e) {
            log.error("文档解析失败: id={}", documentId, e);
            String errorMsg = e.getMessage();
            if (errorMsg == null || errorMsg.isBlank()) {
                errorMsg = e.getClass().getSimpleName();
            }
            handleParseFailure(doc, errorMsg);
        } finally {
            if (needCleanup && sourcePath != null) {
                try {
                    Files.deleteIfExists(sourcePath);
                } catch (IOException e) {
                    log.warn("清理临时文件失败: {}", sourcePath, e);
                }
            }
        }
    }

    /**
     * <h3>解析失败处理（回滚保留旧结果）</h3>
     *
     * <p>若此前存在解析成功的旧结果（{@code parsedTextUrl} 非空），
     * 则回滚状态为 2（已解析）并保留旧结果，仅记录本次失败信息；
     * 否则（首次解析失败）将状态置为 3（失败）。</p>
     *
     * @param doc      当前文档实体
     * @param errorMsg 本次解析失败原因
     */
    private void handleParseFailure(KnowledgeDocument doc, String errorMsg) {
        String oldParsedTextUrl = doc.getParsedTextUrl();
        if (oldParsedTextUrl != null && !oldParsedTextUrl.isBlank()) {
            doc.setStatus(2);
            doc.setErrorMessage("重新解析失败，已保留上次解析结果: " + errorMsg);
        } else {
            doc.setStatus(3);
            doc.setErrorMessage(errorMsg);
        }
        knowledgeDocumentMapper.updateById(doc);
    }

    /**
     * <h3>从远程 URL 下载文件到临时目录</h3>
     *
     * <p>使用 Java 内置 {@link HttpClient}（Java 11+），无需引入第三方依赖。</p>
     *
     * @param fileUrl  远程文件 URL
     * @param fileName 原始文件名（用于临时文件命名）
     * @return 临时文件路径
     */
    private Path downloadToTemp(String fileUrl, String fileName) {
        try {
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "knowledge-parse");
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }
            Path tempFile = tempDir.resolve(fileName);

            try (HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build()) {

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(fileUrl))
                        .timeout(java.time.Duration.ofMinutes(5))
                        .build();

                HttpResponse<InputStream> response = client.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    throw new BusinessException(400,
                            "远程文件下载失败，HTTP " + response.statusCode());
                }

                try (InputStream is = response.body()) {
                    Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            log.info("远程文件已下载到临时目录: {} -> {}", fileUrl, tempFile);
            return tempFile;

        } catch (IOException e) {
            log.error("远程文件下载失败: url={}", fileUrl, e);
            throw new BusinessException(500, "远程文件下载失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "远程文件下载被中断");
        }
    }

    // ======================== 内部状态管理 ========================

    /**
     * <h3>更新文件解析状态（内部方法）</h3>
     *
     * <p>供向量化流程调用，更新文件的解析状态及统计信息。</p>
     *
     * @param id            文件 ID
     * @param status        目标状态：0-待处理, 1-处理中, 2-已完成, 3-失败
     * @param errorMessage  失败时的错误信息（状态为 3 时必填）
     * @param chunkCount    解析出的切片数量
     * @param tokenCount    消耗的 token 数
     */
    public void updateStatus(Long id, Integer status, String errorMessage,
                              Integer chunkCount, Integer tokenCount) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
        if (doc == null) {
            log.warn("更新状态失败，文件不存在: id={}", id);
            return;
        }

        doc.setStatus(status);
        doc.setErrorMessage(errorMessage);
        if (chunkCount != null) doc.setChunkCount(chunkCount);
        if (tokenCount != null) doc.setTokenCount(tokenCount);

        knowledgeDocumentMapper.updateById(doc);

        log.info("文件状态更新: id={}, status={}, chunkCount={}, tokenCount={}",
                id, status, chunkCount, tokenCount);
    }



    // ======================== 工具方法 ========================

    /**
     * Entity → UploadVO 转换（上传接口响应）
     */
    private KnowledgeDocumentUploadVO toUploadVO(KnowledgeDocument doc, String kbName) {
        KnowledgeDocumentUploadVO vo = new KnowledgeDocumentUploadVO();
        vo.setId(doc.getId());
        vo.setTitle(doc.getTitle());
        vo.setKnowledgeBase(kbName);
        vo.setFileName(doc.getFileName());
        vo.setFileType(doc.getFileExt());
        vo.setFileSize(doc.getFileSize());

        vo.setParseStatus(mapParseStatus(doc.getStatus()));
        vo.setVectorStatus(mapVectorStatus(doc.getIsVectorized()));

        if (doc.getCreatedAt() != null) {
            vo.setCreatedAt(doc.getCreatedAt().format(DATETIME_FORMATTER));
        }
        return vo;
    }

    /**
     * status → parseStatus 映射（仅由解析状态字段决定）
     */
    private String mapParseStatus(Integer status) {
        if (status == null) return "unparsed";
        return switch (status) {
            case 1  -> "parsing";
            case 2  -> "parsed";
            case 3  -> "failed";
            default -> "unparsed";
        };
    }

    /**
     * isVectorized → vectorStatus 映射（仅由向量化状态字段决定）
     */
    private String mapVectorStatus(Integer isVectorized) {
        if (isVectorized == null) return "unvectorized";
        return switch (isVectorized) {
            case 1  -> "vectorizing";
            case 2  -> "vectorized";
            case 3  -> "failed";
            default -> "unvectorized";
        };
    }

    /**
     * Entity → VO 转换
     */
    private KnowledgeDocumentVO toVO(KnowledgeDocument doc) {
        KnowledgeDocumentVO vo = new KnowledgeDocumentVO();
        vo.setId(doc.getId());
        vo.setKbId(doc.getKbId());
        vo.setTitle(doc.getTitle());
        vo.setFileName(doc.getFileName());
        vo.setFileExt(doc.getFileExt());
        vo.setFileSize(doc.getFileSize());
        vo.setFileUrl(doc.getFileUrl());
        vo.setFileHash(doc.getFileHash());
        vo.setParsedTextUrl(doc.getParsedTextUrl());
        vo.setStatus(doc.getStatus());
        vo.setIsVectorized(doc.getIsVectorized());

        vo.setParseStatus(mapParseStatus(doc.getStatus()));
        vo.setVectorStatus(mapVectorStatus(doc.getIsVectorized()));

        vo.setErrorMessage(doc.getErrorMessage());
        vo.setChunkCount(doc.getChunkCount());
        vo.setTokenCount(doc.getTokenCount());
        vo.setCreatedBy(doc.getCreatedBy());
        vo.setCreatedAt(doc.getCreatedAt());
        vo.setUpdatedAt(doc.getUpdatedAt());
        vo.setIsDeleted(doc.getIsDeleted());
        return vo;
    }

    // ======================== 片段管理（精细化干预 & 启停控制） ========================

    /**
     * <h3>查询文档已保存片段列表（含主键与启用状态）</h3>
     *
     * <p>区别于切片预览接口，本接口返回 {@code knowledge_document_chunk} 表中的
     * 已保存片段，附带数据库主键 {@code id} 与启用状态 {@code enabled}，
     * 供前端片段管理页展示与操作。</p>
     *
     * @param documentId 文档 ID
     * @param role       所需最低角色（VIEWER/EDITOR）
     */
    public KnowledgeDocumentChunksVO listChunksForDocument(Long documentId, Role role) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在");
        }
        checkDocumentPermission(doc, role);

        LambdaQueryWrapper<KnowledgeDocumentChunk> qw = new LambdaQueryWrapper<>();
        qw.eq(KnowledgeDocumentChunk::getDocumentId, documentId)
                .orderByAsc(KnowledgeDocumentChunk::getChunkIndex)
                .orderByAsc(KnowledgeDocumentChunk::getId);
        List<KnowledgeDocumentChunk> chunks = knowledgeDocumentChunkMapper.selectList(qw);
        return buildChunksResponse(doc, chunks, chunks == null ? 0 : chunks.size());
    }

    /**
     * <h3>编辑单个片段（精细化干预）</h3>
     *
     * <p>修改片段文本或元数据（contextPrefix/contentType/parentText 等），
     * token 数自动重算，若文档已向量化则异步重算该片段的向量与 BM25 索引，
     * 其余片段不受影响。</p>
     *
     * @param chunkId 片段主键
     * @param dto     编辑内容（null 字段保持原值）
     */
    @Transactional
    public KnowledgeDocumentChunksVO.ChunkItem updateChunk(Long chunkId, KnowledgeDocumentChunkUpdateDTO dto) {
        KnowledgeDocumentChunk chunk = getChunkWithPermission(chunkId, Role.EDITOR);
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(chunk.getDocumentId());
        String oldText = chunk.getText();

        if (dto.getText() != null) {
            chunk.setText(dto.getText());
        }
        if (dto.getContextPrefix() != null) {
            chunk.setContextPrefix(dto.getContextPrefix());
        }
        if (dto.getContentType() != null) {
            chunk.setContentType(dto.getContentType());
        }
        if (dto.getParentText() != null) {
            chunk.setParentText(dto.getParentText());
        }
        if (dto.getParentStart() != null) {
            chunk.setParentStart(dto.getParentStart());
        }
        if (dto.getParentEnd() != null) {
            chunk.setParentEnd(dto.getParentEnd());
        }

        // token 数自动重算
        String text = chunk.getText() != null ? chunk.getText() : "";
        chunk.setTokenCount(tokenCountEstimator.estimateTokenCountInText(text));

        knowledgeDocumentChunkMapper.updateById(chunk);
        recalcDocumentStats(chunk.getDocumentId());

        // 文本变化且文档已向量化时重算向量（未向量化文档不重算，向量化状态由全量任务统一管理）
        boolean textChanged = oldText == null || !oldText.equals(text);
        boolean vectorized = doc != null && Integer.valueOf(2).equals(doc.getIsVectorized());
        if (textChanged && vectorized) {
            triggerAfterCommit(() -> self.revectorizeChunkAsync(chunkId));
        }

        return toChunkItem(chunk);
    }

    /**
     * <h3>合并相邻片段（精细化干预）</h3>
     *
     * <p>将 {@code chunkIds} 中多个片段按给定顺序合并为单个片段（文本用换行拼接），
     * 保留首片段的 contextPrefix/contentType，父级段落元数据置空。
     * 被删除片段的向量与 BM25 索引同步清理，合并后的片段异步重算向量。</p>
     */
    @Transactional
    public KnowledgeDocumentChunksVO.ChunkItem mergeChunks(KnowledgeDocumentChunkMergeDTO dto) {
        if (dto == null || dto.getChunkIds() == null || dto.getChunkIds().size() < 2) {
            throw new BusinessException(400, "至少需要 2 个片段才能合并");
        }
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(dto.getDocumentId());
        if (doc == null) {
            throw new BusinessException(404, "文档不存在");
        }
        checkDocumentPermission(doc, Role.EDITOR);

        List<KnowledgeDocumentChunk> chunks = new java.util.ArrayList<>();
        for (Long id : dto.getChunkIds()) {
            KnowledgeDocumentChunk c = knowledgeDocumentChunkMapper.selectById(id);
            if (c == null) {
                throw new BusinessException(404, "片段不存在: id=" + id);
            }
            if (!dto.getDocumentId().equals(c.getDocumentId())) {
                throw new BusinessException(400, "片段不属于该文档: id=" + id);
            }
            chunks.add(c);
        }

        // 首片段吸收全部文本
        KnowledgeDocumentChunk first = chunks.get(0);
        StringBuilder merged = new StringBuilder();
        for (KnowledgeDocumentChunk c : chunks) {
            if (c.getText() != null && !c.getText().isBlank()) {
                if (merged.length() > 0) {
                    merged.append("\n\n");
                }
                merged.append(c.getText());
            }
        }
        String mergedText = merged.toString();
        first.setText(mergedText);
        first.setTokenCount(tokenCountEstimator.estimateTokenCountInText(mergedText));
        // 父级边界在合并后失效，置空
        first.setParentText(null);
        first.setParentStart(null);
        first.setParentEnd(null);
        knowledgeDocumentChunkMapper.updateById(first);

        // 删除其余片段（同步清理 BM25，异步清理向量）
        com.hai.aiknowledgebase.common.KeywordIndex keywordIndex = hybridSearchService.getKeywordIndex();
        for (int i = 1; i < chunks.size(); i++) {
            KnowledgeDocumentChunk c = chunks.get(i);
            keywordIndex.remove(bm25ChunkId(doc, c.getId()));
            knowledgeDocumentChunkMapper.deleteById(c.getId());
            final Long deletedId = c.getId();
            triggerAfterCommit(() -> self.removeChunkVectorAsync(deletedId));
        }

        renormalizeChunkIndexes(dto.getDocumentId());
        recalcDocumentStats(dto.getDocumentId());

        // 文档已向量化时才重算向量（未向量化文档不重算，向量化状态由全量任务统一管理）
        final Long mergedId = first.getId();
        if (Integer.valueOf(2).equals(doc.getIsVectorized())) {
            triggerAfterCommit(() -> self.revectorizeChunkAsync(mergedId));
        }

        return toChunkItem(first);
    }

    /**
     * <h3>拆分片段（精细化干预）</h3>
     *
     * <p>将单个片段拆分为多个片段，原片段被删除，拆分后的文本由 {@code texts} 提供。
     * 新片段继承原片段的 contentType/contextPrefix/enabled 状态，token 数自动重算。</p>
     */
    @Transactional
    public List<KnowledgeDocumentChunksVO.ChunkItem> splitChunk(KnowledgeDocumentChunkSplitDTO dto) {
        if (dto == null || dto.getChunkId() == null || dto.getTexts() == null || dto.getTexts().size() < 2) {
            throw new BusinessException(400, "拆分至少需要 2 段文本");
        }
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(dto.getDocumentId());
        if (doc == null) {
            throw new BusinessException(404, "文档不存在");
        }
        checkDocumentPermission(doc, Role.EDITOR);

        KnowledgeDocumentChunk original = knowledgeDocumentChunkMapper.selectById(dto.getChunkId());
        if (original == null) {
            throw new BusinessException(404, "片段不存在: id=" + dto.getChunkId());
        }
        if (!dto.getDocumentId().equals(original.getDocumentId())) {
            throw new BusinessException(400, "片段不属于该文档: id=" + dto.getChunkId());
        }

        // 删除原片段（同步清理 BM25，异步清理向量）
        com.hai.aiknowledgebase.common.KeywordIndex keywordIndex = hybridSearchService.getKeywordIndex();
        keywordIndex.remove(bm25ChunkId(doc, original.getId()));
        knowledgeDocumentChunkMapper.deleteById(original.getId());
        triggerAfterCommit(() -> self.removeChunkVectorAsync(original.getId()));

        // 插入拆分后的新片段，chunkIndex 从原片段序号开始递增
        List<KnowledgeDocumentChunksVO.ChunkItem> result = new java.util.ArrayList<>();
        int baseIndex = original.getChunkIndex() != null ? original.getChunkIndex() : 0;
        for (int i = 0; i < dto.getTexts().size(); i++) {
            String text = dto.getTexts().get(i);
            KnowledgeDocumentChunk c = new KnowledgeDocumentChunk();
            c.setDocumentId(dto.getDocumentId());
            c.setChunkIndex(baseIndex + i);
            c.setText(text);
            c.setTokenCount(tokenCountEstimator.estimateTokenCountInText(text != null ? text : ""));
            c.setContextPrefix(original.getContextPrefix());
            c.setContentType(original.getContentType());
            c.setEnabled(original.getEnabled() == null || original.getEnabled());
            knowledgeDocumentChunkMapper.insert(c);
            result.add(toChunkItem(c));
        }

        renormalizeChunkIndexes(dto.getDocumentId());
        recalcDocumentStats(dto.getDocumentId());

        // 文档已向量化时才向量化新片段（未向量化文档不向量化，向量化状态由全量任务统一管理）
        List<Long> newIds = result.stream().map(KnowledgeDocumentChunksVO.ChunkItem::getId).collect(java.util.stream.Collectors.toList());
        if (Integer.valueOf(2).equals(doc.getIsVectorized())) {
            triggerAfterCommit(() -> {
                for (Long id : newIds) {
                    self.revectorizeChunkAsync(id);
                }
            });
        }

        return result;
    }

    /**
     * <h3>批量启停片段（召回过滤）</h3>
     *
     * <p>{@code enabled=false} 时片段在检索时被过滤（不参与召回），
     * {@code enabled=true} 时恢复召回。向量数据始终保留，仅检索层过滤，
     * 禁用时同步从 BM25 内存索引移除，启用时重新加入，实现零成本切换。</p>
     */
    @Transactional
    public List<KnowledgeDocumentChunksVO.ChunkItem> setChunksEnabled(KnowledgeDocumentChunkToggleDTO dto) {
        if (dto == null || dto.getChunkIds() == null || dto.getChunkIds().isEmpty()) {
            throw new BusinessException(400, "片段 ID 列表不能为空");
        }
        if (dto.getEnabled() == null) {
            throw new BusinessException(400, "enabled 不能为空");
        }

        // 先校验所有片段与权限，再统一修改，避免中途失败导致内存索引与数据库不一致
        java.util.Map<Long, KnowledgeDocumentChunk> chunkMap = new java.util.LinkedHashMap<>();
        java.util.Map<Long, KnowledgeDocument> docMap = new java.util.HashMap<>();
        for (Long chunkId : dto.getChunkIds()) {
            KnowledgeDocumentChunk chunk = knowledgeDocumentChunkMapper.selectById(chunkId);
            if (chunk == null) {
                throw new BusinessException(404, "片段不存在: id=" + chunkId);
            }
            KnowledgeDocument doc = knowledgeDocumentMapper.selectById(chunk.getDocumentId());
            if (doc == null) {
                throw new BusinessException(404, "文档不存在");
            }
            checkDocumentPermission(doc, Role.EDITOR);
            chunkMap.put(chunkId, chunk);
            docMap.put(chunk.getDocumentId(), doc);
        }

        com.hai.aiknowledgebase.common.KeywordIndex keywordIndex = hybridSearchService.getKeywordIndex();
        List<KnowledgeDocumentChunksVO.ChunkItem> result = new java.util.ArrayList<>();

        for (java.util.Map.Entry<Long, KnowledgeDocumentChunk> entry : chunkMap.entrySet()) {
            Long chunkId = entry.getKey();
            KnowledgeDocumentChunk chunk = entry.getValue();

            chunk.setEnabled(dto.getEnabled());
            knowledgeDocumentChunkMapper.updateById(chunk);

            // 同步向量检索过滤集合
            hybridSearchService.markChunkEnabled(chunkId, dto.getEnabled());

            // 同步 BM25 索引
            KnowledgeDocument doc = docMap.get(chunk.getDocumentId());
            String bm25Id = bm25ChunkId(doc, chunkId);
            if (dto.getEnabled()) {
                if (chunk.getText() != null && !chunk.getText().isBlank()) {
                    keywordIndex.index(bm25Id, chunk.getText());
                }
            } else {
                keywordIndex.remove(bm25Id);
            }

            result.add(toChunkItem(chunk));
        }
        return result;
    }

    /**
     * <h3>加载片段并校验权限</h3>
     */
    private KnowledgeDocumentChunk getChunkWithPermission(Long chunkId, Role role) {
        KnowledgeDocumentChunk chunk = knowledgeDocumentChunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new BusinessException(404, "片段不存在: id=" + chunkId);
        }
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(chunk.getDocumentId());
        if (doc == null) {
            throw new BusinessException(404, "文档不存在");
        }
        checkDocumentPermission(doc, role);
        return chunk;
    }

    /**
     * <h3>重算文档 chunkCount / tokenCount</h3>
     */
    private void recalcDocumentStats(Long documentId) {
        LambdaQueryWrapper<KnowledgeDocumentChunk> qw = new LambdaQueryWrapper<>();
        qw.eq(KnowledgeDocumentChunk::getDocumentId, documentId);
        List<KnowledgeDocumentChunk> chunks = knowledgeDocumentChunkMapper.selectList(qw);
        int count = chunks == null ? 0 : chunks.size();
        int tokens = 0;
        if (chunks != null) {
            for (KnowledgeDocumentChunk c : chunks) {
                if (c.getTokenCount() != null) {
                    tokens += c.getTokenCount();
                }
            }
        }
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc != null) {
            doc.setChunkCount(count);
            doc.setTokenCount(tokens);
            knowledgeDocumentMapper.updateById(doc);
        }
    }

    /**
     * <h3>重排片段序号（合并/拆分后保持 0..n-1 连续）</h3>
     */
    private void renormalizeChunkIndexes(Long documentId) {
        LambdaQueryWrapper<KnowledgeDocumentChunk> qw = new LambdaQueryWrapper<>();
        qw.eq(KnowledgeDocumentChunk::getDocumentId, documentId)
                .orderByAsc(KnowledgeDocumentChunk::getChunkIndex)
                .orderByAsc(KnowledgeDocumentChunk::getId);
        List<KnowledgeDocumentChunk> chunks = knowledgeDocumentChunkMapper.selectList(qw);
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeDocumentChunk c = chunks.get(i);
            if (c.getChunkIndex() == null || c.getChunkIndex() != i) {
                c.setChunkIndex(i);
                knowledgeDocumentChunkMapper.updateById(c);
            }
        }
    }

    /**
     * <h3>构建片段 BM25 索引 ID</h3>
     *
     * <p>格式与 {@link #vectorizeAsync} 保持一致：{@code kbId_fileName_chunkId}。</p>
     */
    private String bm25ChunkId(KnowledgeDocument doc, Long chunkId) {
        String fileName = doc.getFileName() != null ? doc.getFileName() : "document";
        String kbPrefix = doc.getKbId() != null ? doc.getKbId() + "_" : "";
        return kbPrefix + fileName + "_" + chunkId;
    }

    /**
     * <h3>构建片段向量元数据</h3>
     *
     * <p>包含 source/document_id/chunk_index/kb_id/chunk_id 以及内容元数据
     * （content_type/parent_text/parent_start/parent_end），供检索层
     * 父上下文扩展与 content_type 降权使用。</p>
     */
    private Metadata buildChunkMetadata(KnowledgeDocument doc, KnowledgeDocumentChunk chunk) {
        Metadata metadata = Metadata.from("source", doc.getFileName() != null ? doc.getFileName() : "document")
                .put("document_id", String.valueOf(doc.getId()))
                .put("chunk_index", String.valueOf(chunk.getChunkIndex()))
                .put("chunk_id", String.valueOf(chunk.getId()))
                .put("kb_id", doc.getKbId() != null ? String.valueOf(doc.getKbId()) : "");
        if (chunk.getContentType() != null) {
            metadata.put("content_type", chunk.getContentType());
        }
        if (chunk.getParentText() != null) {
            metadata.put("parent_text", chunk.getParentText());
        }
        if (chunk.getParentStart() != null) {
            metadata.put("parent_start", String.valueOf(chunk.getParentStart()));
        }
        if (chunk.getParentEnd() != null) {
            metadata.put("parent_end", String.valueOf(chunk.getParentEnd()));
        }
        return metadata;
    }

    /**
     * <h3>事务提交后触发异步任务</h3>
     *
     * <p>确保数据库变更已提交后再执行异步向量重算，避免异步线程读到旧数据。</p>
     */
    private void triggerAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            // 无事务上下文时直接执行
            task.run();
        }
    }

    /**
     * <h3>异步重算单个片段向量与 BM25 索引</h3>
     */
    @Async("vectorizationTaskExecutor")
    public void revectorizeChunkAsync(Long chunkId) {
        KnowledgeDocumentChunk chunk = knowledgeDocumentChunkMapper.selectById(chunkId);
        if (chunk == null) {
            log.warn("片段向量重算: 片段不存在, id={}", chunkId);
            return;
        }
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(chunk.getDocumentId());
        if (doc == null) {
            log.warn("片段向量重算: 文档不存在, chunkId={}", chunkId);
            return;
        }
        try {
            String text = chunk.getText() != null ? chunk.getText() : "";
            // 删除旧向量后重新写入
            jdbcTemplate.update("DELETE FROM " + vectorTableName + " WHERE metadata->>'chunk_id' = ?",
                    String.valueOf(chunkId));
            Embedding embedding = embeddingModel.embed(text).content();
            embeddingStore.add(embedding, TextSegment.from(text, buildChunkMetadata(doc, chunk)));

            // 更新 BM25
            com.hai.aiknowledgebase.common.KeywordIndex keywordIndex = hybridSearchService.getKeywordIndex();
            String bm25Id = bm25ChunkId(doc, chunkId);
            keywordIndex.remove(bm25Id);
            if (chunk.getEnabled() == null || chunk.getEnabled()) {
                keywordIndex.index(bm25Id, text);
            }
            log.info("片段向量重算完成: chunkId={}", chunkId);
        } catch (Exception e) {
            log.error("片段向量重算失败: chunkId={}", chunkId, e);
        }
    }

    /**
     * <h3>异步删除单个片段向量</h3>
     */
    @Async("vectorizationTaskExecutor")
    public void removeChunkVectorAsync(Long chunkId) {
        try {
            jdbcTemplate.update("DELETE FROM " + vectorTableName + " WHERE metadata->>'chunk_id' = ?",
                    String.valueOf(chunkId));
            log.info("片段向量删除完成: chunkId={}", chunkId);
        } catch (Exception e) {
            log.error("片段向量删除失败: chunkId={}", chunkId, e);
        }
    }
}