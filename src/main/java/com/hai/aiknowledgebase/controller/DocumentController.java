package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.dto.CreateCategoryRequest;
import com.hai.aiknowledgebase.dto.DocumentFileDTO;
import com.hai.aiknowledgebase.dto.DocumentInfo;
import com.hai.aiknowledgebase.dto.DocumentUploadResponse;
import com.hai.aiknowledgebase.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,@RequestParam(value = "category", required = false) String category) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new DocumentUploadResponse(null, 0, "文件不能为空"));
            }

            String filename = file.getOriginalFilename();
            int chunkCount = documentService.uploadDocument(file,category);

            return ResponseEntity.ok(
                new DocumentUploadResponse(filename, chunkCount, "文档上传成功")
            );
        } catch (Exception e) {
            log.error("文档上传失败", e);
            return ResponseEntity.internalServerError()
                .body(new DocumentUploadResponse(
                    file.getOriginalFilename(),
                    0,
                    "上传失败: " + e.getMessage()
                ));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<List<DocumentInfo>> listDocuments() {
        try {
            List<DocumentInfo> documents = documentService.listDocuments();
            return ResponseEntity.ok(documents);
        } catch (Exception e) {
            log.error("查询文档列表失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 创建新分类（同时创建物理文件夹）
     */
    @PostMapping("/category")
    public ResponseEntity<String> createCategory(@RequestBody CreateCategoryRequest request) {
        try {
            documentService.createCategory(request.getName(), request.getDescription());
            return ResponseEntity.ok("分类创建成功: " + request.getName());
        } catch (Exception e) {
            log.error("创建分类失败", e);
            return ResponseEntity.badRequest().body("创建失败: " + e.getMessage());
        }
    }

    /**
     * 删除分类（仅当文件夹为空时）
     */
    @DeleteMapping("/category/{name}")
    public ResponseEntity<String> deleteCategory(@PathVariable String name) {
        try {
            documentService.deleteCategory(name);
            return ResponseEntity.ok("分类删除成功: " + name);
        } catch (Exception e) {
            log.error("删除分类失败", e);
            return ResponseEntity.badRequest().body("删除失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有分类列表（来自 categories 表）
     */
    @GetMapping("/categories")
    public List<String> getCategories() {
        return documentService.getAllCategoriesFromTable();
    }

    /**
     * 获取指定分类下的所有文件列表
     */
    @GetMapping("/category/{categoryName}")
    public ResponseEntity<List<DocumentFileDTO>> getFilesByCategory(@PathVariable String categoryName) {
        log.info("获取分类文件列表: {}", categoryName);

        // 检查分类是否存在
        if (!documentService.categoryExists(categoryName)) {
            return ResponseEntity.ok(Collections.emptyList()); // 分类不存在，返回空列表
        }

        List<DocumentFileDTO> files = documentService.getFilesByCategory(categoryName);
        return ResponseEntity.ok(files);
    }


    /**
     * 按 ID 删除
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteDocument(@PathVariable Long id) {
        try {
            documentService.deleteDocument(id);
            return ResponseEntity.ok("文档删除成功");
        } catch (Exception e) {
            log.error("删除文档失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("删除失败: " + e.getMessage());
        }
    }

}
