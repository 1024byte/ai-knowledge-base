package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRetrieveRequest {
    private String query;
    private String sessionId;
    @Builder.Default
    private int topK = 10;

    /** 评估模式：full=完整管线, no_rewrite=跳过查询改写, no_rerank=跳过精排, vector_only=仅向量检索 */
    @Builder.Default
    private String mode = "full";
}