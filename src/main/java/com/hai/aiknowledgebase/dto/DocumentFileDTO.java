package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentFileDTO {
    private Long id;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String fileType;
    private Integer chunkCount;    // 切分后的文本块数量
    private LocalDateTime uploadTime;
}