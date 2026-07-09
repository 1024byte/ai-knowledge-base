package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO {
    private String role;          // 'user' 或 'assistant'
    private String content;
    private LocalDateTime createTime;
    private List<String> sources;  // 新增
}