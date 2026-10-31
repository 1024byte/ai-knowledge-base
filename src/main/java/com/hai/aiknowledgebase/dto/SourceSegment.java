package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 命中知识片段
 *
 * <p>RAG 检索阶段命中的文档片段，包含片段正文、来源文档名和相关性分数。
 * 供 /api/chat/sources/{messageId} 接口返回给前端展示参考来源。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceSegment {
    private String content;
    private String source;
    private double score;
}