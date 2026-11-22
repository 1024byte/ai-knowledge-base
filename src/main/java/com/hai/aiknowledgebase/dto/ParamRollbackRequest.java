package com.hai.aiknowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <h2>参数回退请求 DTO</h2>
 *
 * <p>删除指定参数在本作用域下的所有配置（草稿 + 已应用），回退到默认值。</p>
 */
@Data
public class ParamRollbackRequest {

    /** 知识库 ID（null = 全局作用域） */
    private Long kbId;

    /** 参数键 */
    @NotBlank(message = "参数键不能为空")
    private String paramKey;
}