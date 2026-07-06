package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ChatSessionDTO {
    private String sessionId;
    private String preview;        // 最后一条消息摘要（前20字）
    private LocalDateTime lastActiveTime;
}