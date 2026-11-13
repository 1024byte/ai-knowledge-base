package com.hai.aiknowledgebase.common;

import lombok.Getter;

/**
 * <h2>知识库成员角色枚举</h2>
 *
 * <p>简化 RBAC：角色用枚举表达，权限等级通过 {@code level} 比较，
 * 等级从高到低为 OWNER &gt; ADMIN &gt; EDITOR &gt; VIEWER。</p>
 */
@Getter
public enum Role {

    OWNER("OWNER", "所有者", 4),
    ADMIN("ADMIN", "管理员", 3),
    EDITOR("EDITOR", "编辑者", 2),
    VIEWER("VIEWER", "只读者", 1);

    /** 库存储标识（对应 kb_members.role） */
    private final String code;

    /** 中文描述 */
    private final String desc;

    /** 权限等级，数值越大权限越高 */
    private final int level;

    Role(String code, String desc, int level) {
        this.code = code;
        this.desc = desc;
        this.level = level;
    }

    /**
     * 判断当前角色是否不低于指定角色。
     *
     * @param required 所需的最低角色
     * @return true 表示当前角色权限足够
     */
    public boolean atLeast(Role required) {
        return this.level >= required.level;
    }

    /**
     * 根据库存储标识反查枚举。
     *
     * @param code 角色标识（大小写不敏感）
     * @return 对应枚举，未匹配或入参为空时返回 null
     */
    public static Role fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (Role role : values()) {
            if (role.code.equalsIgnoreCase(code)) {
                return role;
            }
        }
        return null;
    }
}