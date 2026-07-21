package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hai.aiknowledgebase.common.ResultCode;
import com.hai.aiknowledgebase.dto.DocumentFileDTO;
import com.hai.aiknowledgebase.dto.DocumentInfo;
import com.hai.aiknowledgebase.entity.Category;
import com.hai.aiknowledgebase.entity.DocumentMetadata;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.CategoryMapper;
import com.hai.aiknowledgebase.mapper.DocumentMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hai.aiknowledgebase.common.FileUtils.*;

/**
 * <h2>文档管理服务</h2>
 *
 * <p>负责文档的全生命周期管理，包括上传、查询、删除以及分类管理。</p>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>文档上传</b>：接收文件 → 去重 → 存储 → 元数据入库 → 事务提交后触发异步向量化</li>
 *   <li><b>文档查询</b>：按分类/全量查询文档列表，附带 PGVector chunk 数量统计</li>
 *   <li><b>文档删除</b>：删除向量库 → BM25索引 → 哈希记录 → 物理文件 → 元数据 → 空分类</li>
 *   <li><b>分类管理</b>：创建、删除、查询分类，同步维护物理文件夹</li>
 * </ul>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>事务同步回调</b>：上传时使用 {@link TransactionSynchronizationManager#registerSynchronization}
 *       注册回调，确保<b>只有主事务成功提交后才触发异步向量化</b>。
 *       如果事务回滚（如数据库插入失败），不会产生无效的向量化任务。</li>
 *   <li><b>级联删除</b>：删除文档时依次清理向量库、BM25索引、哈希记录、物理文件、
 *       元数据、空分类，保证数据一致性。</li>
 *   <li><b>目录自动管理</b>：上传时自动创建 `{uploadRoot}/{category}/{yyyy-MM}/` 目录结构；
 *       文件删除后自动向上清理空目录。</li>
 * </ul>
 *
 * @see VectorizationService 异步向量化服务
 * @see HybridSearchService 混合检索服务（提供 BM25 索引）
 * @see DocumentHashService 文档哈希去重服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    /** JDBC Template，仅用于直接操作 PGVector 的 embeddings 表（修改/删除向量数据） */
    private final JdbcTemplate jdbcTemplate;

    /** MyBatis-Plus 文档元数据 Mapper */
    private final DocumentMetadataMapper documentMetadataMapper;

    /** MyBatis-Plus 分类 Mapper */
    private final CategoryMapper categoryMapper;

    /** 异步向量化服务，用于上传后触发向量化流程 */
    private final VectorizationService asyncVectorizationService;

    /** 文档哈希去重服务 */
    private final DocumentHashService documentHashService;

    /** 混合检索服务，提供 BM25 关键词索引（用于删除时清理索引） */
    private final HybridSearchService hybridSearchService;

    /** PGVector 向量表名，默认 "embeddings" */
    @Value("${vectorstore.pgvector.table-name:embeddings}")
    private String tableName;

    /** 文件上传根目录，默认 "./uploads" */
    @Value("${file.upload.root:./uploads}")
    private String uploadRootPath;

    /** 文件上传大小上限（MB），默认 500MB，超过此限制直接拒绝 */
    @Value("${document.upload.max-size-mb:500}")
    private long maxUploadSizeMb;

    // ======================== 文档上传 ========================

    /**
     * <h3>上传文档并按分类存储</h3>
     *
     * <p>这是文档入库的入口方法，完整流程为：</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li><b>参数校验</b>：检查文件非空、文件名合法</li>
     *   <li><b>字节哈希去重</b>：对文件字节计算 SHA-256，快速判断是否完全相同的文件</li>
     *   <li><b>目录生成</b>：构建 {@code {uploadRoot}/{category}/{yyyy-MM}/} 三级目录结构</li>
     *   <li><b>文件存储</b>：将 MultipartFile 写入磁盘</li>
     *   <li><b>元数据入库</b>：将文件名、路径、分类、大小等信息写入 {@code document_metadata} 表</li>
     *   <li><b>分类确保</b>：如果分类不存在则自动创建</li>
     *   <li><b>注册事务回调</b>：<b>关键设计</b>——注册 {@link TransactionSynchronization}，
     *       在 {@code afterCommit()} 中触发异步向量化。只有主事务成功提交后才执行，
     *       如果回滚则不触发，避免产生无效的向量化任务。</li>
     * </ol>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code TransactionSynchronizationManager.registerSynchronization()}</b>：
     *       注册一个事务同步回调。Spring 事务管理器在提交/回滚完成后会调用回调接口。
     *       这是将"异步操作"与"事务生命周期"绑定的标准方式。</li>
     *   <li><b>{@code afterCommit()}</b>：事务<b>成功提交后</b>调用。
     *       此时数据库中的元数据记录已持久化，可以安全地触发异步向量化。</li>
     *   <li><b>{@code afterCompletion(int status)}</b>：事务<b>结束后</b>调用（无论提交还是回滚）。
     *       通过 status 参数判断最终状态，回滚时记录日志。</li>
     *   <li><b>{@code sanitizeFilename()}</b>：清理文件名中的危险字符（如 {@code ../}），
     *       防止路径穿越攻击。</li>
     *   <li><b>{@code generateUniqueFilename()}</b>：为文件名添加时间戳后缀，
     *       避免同名文件覆盖。</li>
     * </ul>
     *
     * @param file     上传的 MultipartFile
     * @param category 文档分类（如 "技术文档"、"规章制度"）
     * @return 文档数据库 ID（重复文件返回 0L）
     */
    @Transactional
    public Long uploadDocument(MultipartFile file, String category) {
        try {
            // 参数校验：文件不能为空
            if (file == null || file.isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "文件不能为空");
            }
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "文件名不能为空");
            }
            log.info("开始处理文档: {}, 分类: {}", originalFilename, category);

            // ===== 步骤0：文件大小校验 =====
            // 前置检查，超过上限直接拒绝，避免大文件撑爆内存和磁盘
            long fileSize = file.getSize();
            long maxBytes = maxUploadSizeMb * 1024 * 1024;
            if (fileSize > maxBytes) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        String.format("文件大小超过上传上限（%dMB），当前文件: %.1fMB",
                                maxUploadSizeMb, fileSize / (1024.0 * 1024.0)));
            }

            // ===== 步骤1：流式 SHA-256 去重 =====
            // 使用 InputStream 流式计算哈希，避免 file.getBytes() 将整个文件加载到内存
            String byteHash = DigestUtils.sha256Hex(file.getInputStream());
            if (documentHashService.exists(byteHash)) {
                return 0L;  // 重复文件，返回 0L 表示跳过
            }

            // ===== 步骤2：处理分类名称 =====
            String normalizedCategory = (category == null || category.trim().isEmpty())
                    ? "未分类"
                    : category.trim();

            // ===== 步骤3：生成安全的文件名 =====
            // sanitizeFilename：移除危险字符（如 ../ 路径穿越）
            // generateUniqueFilename：添加时间戳后缀，避免同名覆盖
            String safeFilename = sanitizeFilename(originalFilename);
            String uniqueFilename = generateUniqueFilename(safeFilename);

            // ===== 步骤4：构建目录结构 =====
            // 格式：{uploadRoot}/{category}/{yyyy-MM}/
            String dateDir = LocalDate.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM"));
            Path uploadDir = Paths.get(uploadRootPath, normalizedCategory, dateDir);
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
                log.info("创建上传目录: {}", uploadDir);
            }

            // ===== 步骤5：保存文件到磁盘 =====
            Path filePath = uploadDir.resolve(uniqueFilename);
            try {
                // StandardCopyOption.REPLACE_EXISTING：如果同名文件存在则覆盖
                Files.copy(file.getInputStream(), filePath,
                        StandardCopyOption.REPLACE_EXISTING);
                log.info("文件已保存到: {}", filePath);
            } catch (IOException e) {
                log.error("文件复制失败: {}", e.getMessage(), e);
                throw new BusinessException(ResultCode.FILE_SAVE_ERROR, e.getMessage());
            }

            // ===== 步骤6：保存元数据到数据库 =====
            DocumentMetadata metadata = new DocumentMetadata();
            metadata.setFileName(originalFilename);
            metadata.setFilePath(filePath.toString());
            metadata.setCategory(normalizedCategory);
            metadata.setFileSize(file.getSize());
            metadata.setFileType(getFileExtension(originalFilename));
            metadata.setStatus("processing");
            // MyBatis-Plus 插入后自动回填主键 ID
            documentMetadataMapper.insert(metadata);
            Long docId = metadata.getId();
            log.info("文档记录已创建: id={}, filePath={}", docId, filePath);

            // ===== 步骤7：确保分类在 categories 表中存在 =====
            ensureCategoryExists(normalizedCategory);

            // ===== 步骤8：注册事务同步回调 =====
            // 关键设计：只有主事务成功提交后，才触发异步向量化。
            // 如果事务回滚，不会触发向量化，避免产生无效的向量数据。
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            // 事务提交成功 → 触发异步向量化
                            log.info("主事务已提交，触发异步向量化: docId={}", docId);
                            asyncVectorizationService.processVectorizationAsync(
                                    docId, filePath, normalizedCategory);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            // 事务结束（无论成功或失败）
                            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                                log.warn("主事务回滚，不触发异步向量化: docId={}", docId);
                            }
                        }
                    }
            );
            return metadata.getId();

        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件上传失败");
        }
    }

    // ======================== 分类管理 ========================

    /**
     * <h3>确保分类在 categories 表中存在</h3>
     *
     * <p>如果分类不存在则自动创建。这是一个幂等操作：多次调用只会在第一次创建记录。</p>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code LambdaQueryWrapper.eq(Category::getName, categoryName)}</b>：
     *       MyBatis-Plus 类型安全的条件构造。通过方法引用指定列名，
     *       编译期即可检测字段名错误，优于字符串硬编码。</li>
     *   <li><b>{@code selectCount(wrapper) == 0}</b>：
     *       先查后插，避免重复插入导致的唯一键冲突。</li>
     * </ul>
     *
     * @param categoryName 分类名称
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
     * <h3>创建新分类</h3>
     *
     * <p>同时创建数据库记录和物理文件夹。</p>
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>校验分类名称非空</li>
     *   <li>检查分类是否已存在（已存在则抛异常）</li>
     *   <li>在磁盘上创建物理文件夹 {@code {uploadRoot}/{categoryName}}</li>
     *   <li>在 categories 表中插入记录</li>
     * </ol>
     *
     * @param categoryName 分类名称
     * @param description  分类描述
     */
    public void createCategory(String categoryName, String description) {
        try {
            if (categoryName == null || categoryName.trim().isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "分类名称不能为空");
            }
            String normalizedName = categoryName.trim();

            // 检查分类是否已存在
            LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Category::getName, normalizedName);
            if (categoryMapper.selectCount(wrapper) > 0) {
                throw new BusinessException(ResultCode.CATEGORY_EXISTS);
            }

            // 创建物理文件夹
            Path categoryDir = Paths.get(uploadRootPath, normalizedName);
            if (!Files.exists(categoryDir)) {
                Files.createDirectories(categoryDir);
                log.info("创建物理文件夹: {}", categoryDir.toAbsolutePath());
            }

            // 插入数据库记录
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
     * <h3>删除分类</h3>
     *
     * <p>同时删除数据库记录和物理文件夹。只能删除空分类（无关联文档）。</p>
     *
     * <h4>安全校验</h4>
     * <ul>
     *   <li>检查数据库中该分类下是否有 active 状态的文档</li>
     *   <li>检查物理文件夹是否为空</li>
     *   <li>任一条件不满足则抛异常拒绝删除</li>
     * </ul>
     *
     * @param categoryName 分类名称
     */
    public void deleteCategory(String categoryName) {
        try {
            if (categoryName == null || categoryName.trim().isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "分类名称不能为空");
            }

            // 校验1：数据库层面——该分类下是否有活跃或处理中的文档
            LambdaQueryWrapper<DocumentMetadata> docWrapper = new LambdaQueryWrapper<>();
            docWrapper.eq(DocumentMetadata::getCategory, categoryName)
                    .in(DocumentMetadata::getStatus, "active", "processing");
            Long docCount = documentMetadataMapper.selectCount(docWrapper);
            if (docCount != null && docCount > 0) {
                throw new BusinessException(ResultCode.CATEGORY_NOT_EMPTY);
            }

            // 校验2：物理层面——文件夹是否为空
            Path categoryDir = Paths.get(uploadRootPath, categoryName);
            if (Files.exists(categoryDir)) {
                // Files.list() 返回目录下的直接子项流
                // findAny() 检查是否至少有一个子项
                try (var stream = Files.list(categoryDir)) {
                    if (stream.findAny().isPresent()) {
                        throw new BusinessException(ResultCode.CATEGORY_NOT_EMPTY,
                                "物理文件夹不为空");
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
     * <h3>获取所有分类列表</h3>
     *
     * <p>按创建时间降序排列，返回分类名称列表。</p>
     *
     * @return 分类名称列表
     */
    public List<String> getAllCategoriesFromTable() {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Category::getCreateTime);
        List<Category> categories = categoryMapper.selectList(wrapper);
        return categories.stream()
                .map(Category::getName)
                .collect(Collectors.toList());
    }

    /**
     * <h3>检查分类是否存在</h3>
     *
     * @param categoryName 分类名称
     * @return true 存在，false 不存在
     */
    public boolean categoryExists(String categoryName) {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getName, categoryName);
        return categoryMapper.selectCount(wrapper) > 0;
    }

    /**
     * <h3>检查某个分类下是否还有非失败状态的文档</h3>
     *
     * <p>用于删除分类前的安全校验，以及删除文档后判断是否需要自动清理空分类。
     * 同时检查 processing 和 active 状态的文档，避免在异步处理中误删分类。</p>
     *
     * @param category 分类名称
     * @return true 有活跃/处理中文档，false 无
     */
    private boolean hasFilesInCategory(String category) {
        LambdaQueryWrapper<DocumentMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentMetadata::getCategory, category)
                .in(DocumentMetadata::getStatus, "active", "processing");
        return documentMetadataMapper.selectCount(wrapper) > 0;
    }

    /**
     * <h3>删除空分类</h3>
     *
     * <p>当分类下没有活跃文件时，自动删除分类的数据库记录和物理文件夹。</p>
     *
     * @param category 分类名称
     */
    private void deleteEmptyCategory(String category) throws IOException {
        // 删除分类数据库记录
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getName, category);
        int rows = categoryMapper.delete(wrapper);
        if (rows > 0) {
            log.info("分类数据库记录已删除: {}", category);
        }

        // 删除物理文件夹（仅当为空时）
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

    // ======================== 文档查询 ========================

    /**
     * <h3>查询所有上传的历史文档列表</h3>
     *
     * <p>返回所有状态的文档，按上传时间降序排列。前端可通过 status 字段区分处理状态。</p>
     *
     * <h4>状态说明</h4>
     * <ul>
     *   <li><b>processing</b>：异步向量化处理中，暂不可检索</li>
     *   <li><b>active</b>：处理完成，可正常检索</li>
     *   <li><b>failed</b>：处理失败，需查看 errorMessage</li>
     * </ul>
     *
     * <h4>附加信息</h4>
     * <p>每个文档会附带其在 PGVector 中的 chunk 数量，
     * 通过 {@link #getChunkCountMap} 从 PGVector 的 embeddings 表中查询。</p>
     *
     * @return 文档信息列表（含 chunk 数量和处理状态）
     */
    public List<DocumentInfo> listDocuments() {
        log.info("查询历史文档列表");

        LambdaQueryWrapper<DocumentMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(DocumentMetadata::getUploadTime);
        List<DocumentMetadata> list = documentMetadataMapper.selectList(wrapper);

        if (list.isEmpty()) {
            log.info("暂无文档");
            return Collections.emptyList();
        }

        // 批量查询每个文档的 chunk 数量
        Map<String, Integer> chunkCountMap = getChunkCountMap();

        // 转换为 DTO，附加 chunk 数量、状态、错误信息
        return list.stream().map(metadata -> {
            int chunkCount = chunkCountMap.getOrDefault(
                    metadata.getFileName(), 0);
            return new DocumentInfo(
                    metadata.getId(),
                    metadata.getFileName(),
                    metadata.getFileSize(),
                    metadata.getFileType(),
                    // LocalDateTime → Instant → epoch millis
                    metadata.getUploadTime()
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli(),
                    chunkCount,
                    metadata.getStatus(),
                    metadata.getErrorMessage()
            );
        }).collect(Collectors.toList());
    }

    /**
     * <h3>从 PGVector 表中查询每个 source 对应的 chunk 数量</h3>
     *
     * <h4>SQL 示例</h4>
     * <pre>
     * SELECT metadata->>'source' AS source, COUNT(*) AS cnt
     * FROM embeddings
     * GROUP BY metadata->>'source'
     * </pre>
     *
     * <p>使用 PostgreSQL 的 JSON 操作符 {@code ->>} 提取 metadata 中的 source 字段进行分组聚合。</p>
     *
     * <p>查询失败时返回空 Map，不影响主流程——文档列表仍可正常返回，只是 chunk 数量为 0。</p>
     *
     * @return source → chunkCount 的映射
     */
    public Map<String, Integer> getChunkCountMap() {
        try {
            // PostgreSQL JSON 操作符：->> 返回文本值，-> 返回 JSON 对象
            String sql = String.format(
                    "SELECT metadata->>'source' AS source, COUNT(*) AS cnt " +
                            "FROM %s GROUP BY metadata->>'source'",
                    tableName
            );
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            return rows.stream().collect(Collectors.toMap(
                    row -> String.valueOf(row.get("source")),
                    row -> ((Number) row.get("cnt")).intValue()
            ));
        } catch (Exception e) {
            log.warn("查询 PGVector chunk 数量失败: {}", e.getMessage());
            return Map.of();  // 降级返回空 Map，不影响主流程
        }
    }

    /**
     * <h3>根据分类名称获取该分类下的所有文件列表</h3>
     *
     * <p>仅返回 active 状态的文件，按上传时间降序排列。附带 chunk 数量信息。</p>
     *
     * @param categoryName 分类名称
     * @return 文件 DTO 列表（含 chunk 数量）
     */
    public List<DocumentFileDTO> getFilesByCategory(String categoryName) {
        LambdaQueryWrapper<DocumentMetadata> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DocumentMetadata::getCategory, categoryName)
                .eq(DocumentMetadata::getStatus, "active")
                .orderByDesc(DocumentMetadata::getUploadTime);
        List<DocumentMetadata> list = documentMetadataMapper.selectList(wrapper);

        // 批量查询 chunk 数量
        Map<String, Integer> chunkCountMap = getChunkCountMap();

        return list.stream().map(metadata -> {
            int chunkCount = chunkCountMap.getOrDefault(
                    metadata.getFileName(), 0);
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

    // ======================== 文档删除 ========================

    /**
     * <h3>删除文档及其所有关联数据</h3>
     *
     * <p>这是一个级联删除操作，确保数据一致性。删除顺序很重要：</p>
     *
     * <h4>删除顺序（由里到外）</h4>
     * <ol>
     *   <li><b>向量库</b>：删除 PGVector 中该文档的所有向量片段</li>
     *   <li><b>BM25 索引</b>：删除关键词倒排索引中该文档的片段</li>
     *   <li><b>哈希记录</b>：删除字节级和文本级哈希记录（数据库 + 缓存）</li>
     *   <li><b>物理文件</b>：删除磁盘上的原始文件</li>
     *   <li><b>空目录清理</b>：向上清理空的日期目录和分类目录</li>
     *   <li><b>元数据</b>：删除 document_metadata 表中的记录</li>
     *   <li><b>空分类清理</b>：如果该分类下已无文件，自动删除分类</li>
     * </ol>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code deleteVectorsByDocId(docId)}</b>：
     *       直接使用 JDBC 执行 SQL 删除 PGVector 的 embeddings 表。
     *       因为 LangChain4j 的 PGVector 组件没有提供按 metadata 删除的 API，
     *       所以需要绕过它直接操作数据库。</li>
     *   <li><b>{@code keywordIndex.removeByPrefix(docId + "_")}</b>：
     *       删除 BM25 索引中所有以 "docId_" 开头的记录。
     *       对应向量化时创建的 chunkId 命名规则。</li>
     *   <li><b>{@code cleanupEmptyParentDirs()}</b>：
     *       从文件所在目录开始，向上逐级检查并清理空目录，
     *       直到 uploadRootPath 为止。确保不留下空目录垃圾。</li>
     * </ul>
     *
     * @param documentId 文档在数据库中的唯一标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long documentId) {
        try {
            // 先查询元数据，获取文件名、路径、分类
            DocumentMetadata metadata = documentMetadataMapper.selectById(documentId);
            if (metadata == null) {
                throw new BusinessException(ResultCode.DOCUMENT_NOT_FOUND);
            }
            String fileName = metadata.getFileName();
            String filePath = metadata.getFilePath();
            String category = metadata.getCategory();
            log.info("开始删除文档: id={}, fileName={}", documentId, fileName);

            // 步骤1：删除 PGVector 向量库中的向量数据
            deleteVectorsByDocId(documentId);

            // 步骤2：删除 BM25 关键词倒排索引
            // removeByPrefix 删除所有以 "docId_" 开头的索引条目
            hybridSearchService.getKeywordIndex().removeByPrefix(documentId + "_");
            log.info("已从 BM25 索引中删除文档 {} 的片段", documentId);

            // 步骤3：删除文件哈希记录（数据库 + 缓存）
            documentHashService.deleteByFileName(documentId);

            // 步骤4：删除物理文件
            Path physicalFile = Paths.get(filePath);
            if (Files.exists(physicalFile)) {
                Files.delete(physicalFile);
                log.info("物理文件已删除: {}", physicalFile);
            } else {
                log.warn("物理文件不存在，跳过删除: {}", physicalFile);
            }

            // 步骤5：清理空的父目录（日期目录 → 分类目录）
            cleanupEmptyParentDirs(physicalFile.getParent());

            // 步骤6：删除元数据记录
            int rows = documentMetadataMapper.deleteById(documentId);
            if (rows == 0) {
                throw new BusinessException(ResultCode.DOCUMENT_NOT_FOUND);
            }

            // 步骤7：如果该分类下已无文件，自动清理空分类
            if (!hasFilesInCategory(category)) {
                deleteEmptyCategory(category);
                log.info("分类已无文件，自动删除空分类: {}", category);
            }

            log.info("文档删除完成: id={}, fileName={}",
                    documentId, fileName);

        } catch (IOException e) {
            throw new BusinessException(ResultCode.VECTOR_DELETE_ERROR, "删除向量失败");
        }
    }

    /**
     * <h3>根据文档 ID 删除 PGVector 向量库中的向量片段</h3>
     *
     * <p>直接使用 JDBC SQL 操作 PGVector 的 embeddings 表。
     * 因为 LangChain4j 的 {@code PgVectorEmbeddingStore} 没有提供按 metadata 条件删除的 API，
     * 所以需要绕过它直接操作数据库。</p>
     *
     * <h4>SQL 示例</h4>
     * <pre>
     * DELETE FROM embeddings WHERE metadata->>'document_id' = '42'
     * </pre>
     *
     * <p>使用 PostgreSQL 的 JSON 操作符 {@code ->>} 提取 metadata 中的 document_id 字段进行匹配。</p>
     *
     * @param docId 文档 ID
     */
    private void deleteVectorsByDocId(Long docId) {
        String sql = "DELETE FROM embeddings WHERE metadata->>'document_id' = ?";
        int deleted = jdbcTemplate.update(sql, String.valueOf(docId));
        log.info("从向量库删除了 {} 个与文件 '{}' 相关的向量", deleted, docId);
    }

    // ======================== 文档内容读取 ========================

    /**
     * <h3>根据文档 ID 查询文档元数据（用于文件流式返回）</h3>
     *
     * <p>只查询数据库元数据，不读取文件内容。文件内容的流式传输由 Controller 层负责。</p>
     *
     * <h4>设计考量</h4>
     * <ul>
     *   <li><b>不分文件类型</b>：PDF、Word、图片等二进制文件无法用 {@code Files.readString()} 读取，
     *       正确做法是返回文件流让浏览器渲染</li>
     *   <li><b>不加载到内存</b>：大文件（几百 MB）如果一次性读到 String 会导致 OOM，
     *       应使用 {@code FileSystemResource} + {@code InputStreamResource} 流式传输</li>
     *   <li><b>Service 只查元数据</b>：文件流的管理（Content-Type、Content-Disposition 等 HTTP 头）
     *       属于 Controller 层职责，Service 不应关心</li>
     * </ul>
     *
     * @param documentId 文档在数据库中的唯一标识
     * @return 文档元数据（包含 filePath、fileName、fileType、fileSize）
     * @throws BusinessException 文档不存在时抛出
     */
    public DocumentMetadata getDocumentFileInfo(Long documentId) {
        DocumentMetadata metadata = documentMetadataMapper.selectById(documentId);
        if (metadata == null) {
            throw new BusinessException(ResultCode.DOCUMENT_NOT_FOUND);
        }
        return metadata;
    }

    /**
     * <h3>从指定目录开始，向上逐级清理空目录</h3>
     *
     * <p>从文件所在目录开始，向上逐级检查：</p>
     * <ol>
     *   <li>如果目录为空 → 删除，继续向上</li>
     *   <li>如果目录非空 → 停止，不再向上</li>
     *   <li>到达 uploadRootPath → 停止</li>
     * </ol>
     *
     * <h4>示例</h4>
     * <pre>
     * 文件路径: ./uploads/技术文档/2026-07/章程.pdf
     * 删除后:
     *   检查 ./uploads/技术文档/2026-07/ → 空 → 删除
     *   检查 ./uploads/技术文档/          → 非空 → 停止
     * </pre>
     *
     * @param dir 起始目录（文件删除后所在的目录）
     */
    private void cleanupEmptyParentDirs(Path dir) throws IOException {
        Path root = Paths.get(uploadRootPath).toAbsolutePath();
        Path current = dir.toAbsolutePath();

        while (current != null && !current.equals(root)) {
            if (Files.exists(current)) {
                // Files.list() 返回目录下的直接子项流
                // findAny() 检查是否至少有一个子项
                try (var stream = Files.list(current)) {
                    if (!stream.findAny().isPresent()) {
                        // 目录为空 → 删除
                        Files.delete(current);
                        log.info("空目录已删除: {}", current);
                    } else {
                        // 目录非空 → 停止向上清理
                        break;
                    }
                }
            }
            // 获取父目录，继续向上
            current = current.getParent();
        }
    }
}