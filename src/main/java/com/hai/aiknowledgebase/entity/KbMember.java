package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <h2>知识库成员实体</h2>
 *
 * <p>对应数据库表 {@code kb_members}，表达「用户 ↔ 知识库 ↔ 角色」的关联关系。</p>
 *
 * <p>成员关系采用物理删除（无 {@code @TableLogic}），
 * 删除知识库或用户时由外键 {@code ON DELETE CASCADE} 自动清理。</p>
 */
@Data
@TableName("kb_members")
public class KbMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库 ID */
    private Long kbId;

    /** 用户 ID */
    private Long userId;

    /** 角色标识：OWNER/ADMIN/EDITOR/VIEWER */
    private String role;

    /** 添加该成员的操作者 ID */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}