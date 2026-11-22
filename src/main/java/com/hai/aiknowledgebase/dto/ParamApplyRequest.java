package com.hai.aiknowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <h2>参数应用草稿请求 DTO</h2>
 *
 * <p>将指定参数的草稿置为已应用（生产生效）。{@code kbId} 为 null 表示全局作用域。</p>
 */
@Data
public class ParamApplyRequest {

    /** 知识库 ID（null = 全局作用域） */
    private Long kbId;

    /** 参数键 */
    @NotBlank(message = "参数键不能为空")
    private String paramKey;
}