package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 查询改写请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewriteRequest {

    /** 用户原始查询 */
    private String query;

    /** 截断后的对话历史（最近2轮，每条AI回答截断至200字） */
    private List<ChatMessage> truncatedHistory;

    /** 意图路由决策结果 */
    private RoutingDecision routingDecision;

    /** 会话ID（用于缓存Key） */
    private String sessionId;
}
