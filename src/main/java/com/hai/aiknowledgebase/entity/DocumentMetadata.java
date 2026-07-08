package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_metadata")
public class DocumentMetadata {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileName;
    private String filePath;
    private String category;
    private Long fileSize;
    private String fileType;
    private String uploadUserId;

    @TableField("status")
    private String status;  // processing | active | failed

    @TableField("error_message")
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime uploadTime;
}