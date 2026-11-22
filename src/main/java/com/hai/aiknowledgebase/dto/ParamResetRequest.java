package com.hai.aiknowledgebase.dto;

import lombok.Data;

/**
 * <h2>参数重置请求 DTO</h2>
 *
 * <p>清空本作用域下的所有参数配置（草稿 + 已应用）。{@code kbId} 为 null 表示全局作用域。</p>
 */
@Data
public class ParamResetRequest {

    /** 知识库 ID（null = 全局作用域） */
    private Long kbId;
}