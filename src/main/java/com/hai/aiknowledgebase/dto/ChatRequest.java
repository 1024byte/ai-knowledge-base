package com.hai.aiknowledgebase.dto;

import lombok.Data;

@Data
public class ChatRequest {
    private String sessionId;
    private String question;
    private int topK = 3;
}