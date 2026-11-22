package com.hai.aiknowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <h2>参数保存草稿请求 DTO</h2>
 *
 * <p>{@code kbId} 为 null 表示全局作用域，非 null 表示知识库级覆盖。</p>
 */
@Data
public class ParamSaveRequest {

    /** 知识库 ID（null = 全局作用域） */
    private Long kbId;

    /** 参数键，如 retrieval.vectorMinScore */
    @NotBlank(message = "参数键不能为空")
    private String paramKey;

    /** 参数值（字符串，服务层按元数据类型校验） */
    @NotBlank(message = "参数值不能为空")
    private String paramValue;
}