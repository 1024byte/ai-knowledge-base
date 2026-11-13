package com.hai.aiknowledgebase.dto;

import lombok.Data;

@Data
public class ChatRequest {
    private String sessionId;
    private String question;

    /** 召回数量，不传时使用服务端默认值（chat.retrieval.top-k=15） */
    private Integer topK;

    /** 最低相似度阈值（0.0~1.0），不传时使用服务端默认值（thresholds.retrieval.vector-min-score） */
    private Double minScore;

    /** 知识库 ID，用于检索/问答数据隔离；不传时在用户可见知识库范围内检索 */
    private Long kbId;
}