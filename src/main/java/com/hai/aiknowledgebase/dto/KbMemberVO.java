package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h2>知识库成员响应 DTO</h2>
 *
 * <p>承载 {@code kb_members} 记录，并关联 {@code users} 表补充用户名。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbMemberVO {

    /** 成员关系 ID（kb_members.id） */
    private Long memberId;

    /** 用户 ID */
    private Long userId;

    /** 用户名（关联 users 表） */
    private String username;

    /** 角色标识：OWNER/ADMIN/EDITOR/VIEWER */
    private String role;

    /** 加入时间 */
    private LocalDateTime createdAt;
}