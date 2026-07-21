package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentInfo {
    private Long id;
    private String filename;
    private long fileSize;
    private String fileType;
    private long uploadTime;
    private int chunkCount;
    private String status;
    private String errorMessage;
}