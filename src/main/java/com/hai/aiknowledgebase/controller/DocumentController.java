package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.dto.DocumentInfo;
import com.hai.aiknowledgebase.dto.DocumentUploadResponse;
import com.hai.aiknowledgebase.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new DocumentUploadResponse(null, 0, "文件不能为空"));
            }

            String filename = file.getOriginalFilename();
            int chunkCount = documentService.uploadDocument(file);

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
}
