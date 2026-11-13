package com.hai.aiknowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <h2>知识库成员添加/修改请求 DTO</h2>
 *
 * <p>添加成员时需传 {@code userId} + {@code role}；
 * 修改角色时仅需传 {@code role}（目标用户由路径参数提供）。</p>
 */
@Data
public class KbMemberRequest {

    /** 用户 ID（仅添加成员时必填，修改角色时忽略） */
    private Long userId;

    /** 角色标识：OWNER/ADMIN/EDITOR/VIEWER */
    @NotBlank(message = "成员角色不能为空")
    private String role;
}