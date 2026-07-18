package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.CreateCategoryRequest;
import com.hai.aiknowledgebase.dto.DocumentFileDTO;
import com.hai.aiknowledgebase.dto.DocumentInfo;
import com.hai.aiknowledgebase.dto.DocumentUploadResponse;
import com.hai.aiknowledgebase.entity.DocumentMetadata;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.service.DocumentService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档
     */
    @PostMapping("/upload")
    public Result<DocumentUploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false) String category)  {

        log.info("上传文档: fileName={}, category={}", file.getOriginalFilename(), category);

        if (file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }

        String filename = file.getOriginalFilename();
        Long metaId = documentService.uploadDocument(file, category);
        if (metaId == 0L){
            throw new BusinessException(500, "文档元数据已存在");
        }
        return Result.success(new DocumentUploadResponse(filename, metaId, "文档上传成功"));
    }

    /**
     * 获取所有文档列表
     */
    @GetMapping("/list")
    public Result<List<DocumentInfo>> listDocuments() {
        log.info("获取文档列表");
        List<DocumentInfo> documents = documentService.listDocuments();
        return Result.success(documents);
    }

    /**
     * 创建新分类（同时创建物理文件夹）
     */
    @PostMapping("/category")
    public Result<Void> createCategory(@RequestBody CreateCategoryRequest request) {
        log.info("创建分类: {}", request.getName());

        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException(400, "分类名称不能为空");
        }

        documentService.createCategory(request.getName(), request.getDescription());
        return Result.success();
    }

    /**
     * 删除分类（仅当文件夹为空时）
     */
    @DeleteMapping("/category/{name}")
    public Result<Void> deleteCategory(@PathVariable String name) {
        log.info("删除分类: {}", name);

        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException(400, "分类名称不能为空");
        }

        documentService.deleteCategory(name);
        return Result.success();
    }

    /**
     * 获取所有分类列表（来自 categories 表）
     */
    @GetMapping("/categories")
    public Result<List<String>> getCategories() {
        log.info("获取分类列表");
        List<String> categories = documentService.getAllCategoriesFromTable();
        return Result.success(categories);
    }

    /**
     * 获取指定分类下的所有文件列表
     */
    @GetMapping("/category/{categoryName}")
    public Result<List<DocumentFileDTO>> getFilesByCategory(@PathVariable String categoryName) {
        log.info("获取分类文件列表: {}", categoryName);

        if (categoryName == null || categoryName.trim().isEmpty()) {
            throw new BusinessException(400, "分类名称不能为空");
        }

        // 检查分类是否存在
        if (!documentService.categoryExists(categoryName)) {
            return Result.success(Collections.emptyList()); // 分类不存在，返回空列表
        }

        List<DocumentFileDTO> files = documentService.getFilesByCategory(categoryName);
        return Result.success(files);
    }

    /**
     * 按 ID 删除文档
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        log.info("删除文档: id={}", id);

        if (id == null || id <= 0) {
            throw new BusinessException(400, "文档ID无效");
        }

        documentService.deleteDocument(id);
        return Result.success();
    }

    /**
     * <h3>根据文档 ID 流式返回文件（支持浏览器在线预览）</h3>
     *
     * <p>通过 document_id 查询文档元数据，定位物理文件，以流式方式返回文件内容。
     * 浏览器可直接渲染 PDF、图片等格式，不会将大文件一次性加载到内存。</p>
     *
     * <h4>与旧设计的区别</h4>
     * <ul>
     *   <li><b>旧方案</b>：{@code Files.readString()} → 返回 String → PDF 乱码 / 大文件 OOM</li>
     *   <li><b>新方案</b>：{@code Files.copy(inputStream, outputStream)} → 边读边写 → 内存恒定</li>
     * </ul>
     *
     * <h4>Content-Type 映射</h4>
     * <table>
     *   <tr><td>pdf</td><td>application/pdf</td><td>浏览器内嵌 PDF 阅读器</td></tr>
     *   <tr><td>png/jpg/jpeg/gif/bmp</td><td>image/*</td><td>浏览器直接渲染图片</td></tr>
     *   <tr><td>txt</td><td>text/plain; charset=UTF-8</td><td>浏览器直接显示文本</td></tr>
     *   <tr><td>其他</td><td>application/octet-stream</td><td>触发下载</td></tr>
     * </table>
     *
     * <h4>Content-Disposition 策略</h4>
     * <ul>
     *   <li>PDF / 图片 → {@code inline}：浏览器内嵌预览，不触发下载弹窗</li>
     *   <li>其他 → {@code attachment}：触发下载弹窗</li>
     * </ul>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code Files.copy(inputStream, response.getOutputStream())}</b>：
     *       Java NIO 的零拷贝流式传输，使用 8KB 缓冲区边读边写，
     *       无论文件多大，内存占用恒定为 8KB</li>
     *   <li><b>{@code URLEncoder.encode(fileName, StandardCharsets.UTF_8)}</b>：
     *       对中文文件名进行 URL 编码，避免浏览器下载时文件名乱码</li>
     *   <li><b>{@code Content-Length}</b>：
     *       提前设置文件大小，浏览器可以显示下载进度条</li>
     * </ul>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * GET /api/documents/42/content
     * // 浏览器直接内嵌显示 PDF，地址栏不变
     * }</pre>
     *
     * @param id       文档在数据库中的唯一标识
     * @param response HttpServletResponse，用于写入文件流
     */
    @GetMapping("/{id}/content")
    public void getDocumentContent(@PathVariable Long id, HttpServletResponse response) {
        log.info("获取文档内容: id={}", id);

        if (id == null || id <= 0) {
            throw new BusinessException(400, "文档ID无效");
        }

        // 查询文档元数据
        DocumentMetadata metadata = documentService.getDocumentFileInfo(id);
        String fileName = metadata.getFileName();
        Path filePath = Paths.get(metadata.getFilePath());

        // 校验物理文件
        if (!Files.exists(filePath)) {
            throw new BusinessException(400, "物理文件不存在: " + metadata.getFilePath());
        }

        try {
            // 根据文件扩展名设置 Content-Type
            String contentType = getContentType(fileName);
            response.setContentType(contentType);

            // 设置 Content-Disposition: PDF/图片 → inline（预览），其他 → attachment（下载）
            boolean inline = isInlinePreview(fileName);
            String disposition = inline ? "inline" : "attachment";
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    disposition + "; filename*=UTF-8''" + encodedFileName);

            // 设置文件大小，浏览器可显示进度条
            response.setContentLengthLong(Files.size(filePath));

            // 流式传输：边读边写，内存恒定
            try (OutputStream out = response.getOutputStream()) {
                Files.copy(filePath, out);
                out.flush();
            }

            log.info("文件流式传输完成: id={}, fileName={}, size={} bytes",
                    id, fileName, Files.size(filePath));

        } catch (IOException e) {
            log.error("文件流式传输失败: id={}, path={}", id, metadata.getFilePath(), e);
            throw new BusinessException(400, "文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 根据文件扩展名返回对应的 MIME Type。
     * PDF/图片返回特定类型供浏览器内嵌预览，其他返回通用二进制流。
     */
    private String getContentType(String fileName) {
        String ext = fileName.toLowerCase();
        if (ext.endsWith(".pdf"))  return "application/pdf";
        if (ext.endsWith(".png"))  return "image/png";
        if (ext.endsWith(".jpg") || ext.endsWith(".jpeg")) return "image/jpeg";
        if (ext.endsWith(".gif"))  return "image/gif";
        if (ext.endsWith(".bmp"))  return "image/bmp";
        if (ext.endsWith(".txt"))  return "text/plain; charset=UTF-8";
        if (ext.endsWith(".md"))   return "text/markdown; charset=UTF-8";
        return "application/octet-stream";
    }

    /**
     * 判断文件是否支持浏览器内嵌预览。
     * PDF 和常见图片格式返回 true，浏览器在标签页内直接渲染而不是触发下载。
     */
    private boolean isInlinePreview(String fileName) {
        String ext = fileName.toLowerCase();
        return ext.endsWith(".pdf")
                || ext.endsWith(".png")
                || ext.endsWith(".jpg")
                || ext.endsWith(".jpeg")
                || ext.endsWith(".gif")
                || ext.endsWith(".bmp");
    }


}