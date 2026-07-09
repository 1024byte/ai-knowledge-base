package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_history")
public class ChatHistory {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private String userId;
    private String role;
    private String content;

    @TableField("source_info")
    private String sourceInfo;  // JSON 字符串

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}