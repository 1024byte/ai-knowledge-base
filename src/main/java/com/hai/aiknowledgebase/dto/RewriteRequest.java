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
    private List<CustomChatMessage> truncatedHistory;

    /** 按需改写策略 */
    private RewriteStrategyEnum strategy;

    /** 会话ID（用于缓存Key） */
    private String sessionId;

    /** 是否绕过改写缓存（召回测试等调参场景使用），默认 false */
    @Builder.Default
    private Boolean bypassCache = false;

    /**
     * 是否强制开启改写：无视全局总开关 queryRewrite.enabled。
     * 仅召回测试等调参场景使用（enableRewrite=true 时置为 true），生产链路不传。
     * 注意：只跳过总开关闸门，不跳过 LLM 降级开关 queryRewrite.llm.enabled。
     */
    @Builder.Default
    private Boolean forceEnable = false;
}