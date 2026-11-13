package com.hai.aiknowledgebase.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <h2>创建知识库成员请求 DTO</h2>
 *
 * <p>用于「一步创建用户并加入知识库」场景。若用户名已存在则复用该用户直接加入
 * （此时 {@code password} 可省略），仅在创建新用户时才要求 {@code password}。</p>
 */
@Data
public class KbMemberCreateRequest {

    /** 用户名（必填） */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 初始密码（创建新用户时必填；复用已有用户时可省略） */
    private String password;

    /** 成员角色：OWNER/ADMIN/EDITOR/VIEWER（必填） */
    @NotBlank(message = "成员角色不能为空")
    private String role;

    /** 邮箱（可选） */
    @Email(message = "邮箱格式不正确")
    private String email;
}