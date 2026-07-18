package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.CreateCategoryRequest;
import com.hai.aiknowledgebase.dto.DocumentFileDTO;
import com.hai.aiknowledgebase.dto.DocumentInfo;
import com.hai.aiknowledgebase.dto.DocumentUploadResponse;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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


}