package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h2>用户搜索结果 DTO</h2>
 *
 * <p>仅返回用户 ID 与用户名，绝不包含 password 等敏感字段。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSearchVO {

    /** 用户 ID */
    private Long id;

    /** 用户名 */
    private String username;
}