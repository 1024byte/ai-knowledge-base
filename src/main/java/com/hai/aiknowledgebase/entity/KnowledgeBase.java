package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <h2>知识库主表实体</h2>
 *
 * <p>对应数据库表 {@code knowledge_base}，记录知识库的基本信息与切片参数。</p>
 *
 * <h3>字段说明</h3>
 * <table>
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>name</td><td>VARCHAR(100)</td><td>知识库名称（未删除范围内唯一）</td></tr>
 *   <tr><td>chunk_length / overlap_length</td><td>INT</td><td>切片参数，控制文档入库时的切分粒度</td></tr>
 *   <tr><td>status</td><td>SMALLINT</td><td>0-禁用，1-启用</td></tr>
 *   <tr><td>is_deleted</td><td>SMALLINT</td><td>逻辑删除标记，0-正常，1-已删除</td></tr>
 * </table>
 *
 * @see KnowledgeBaseConfig 高级配置实体（1:1 关联）
 */
@Data
@TableName("knowledge_base")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Integer chunkLength;

    private Integer overlapLength;

    private Integer status;

    private Long createdBy;

    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 是否公开：true-所有登录用户可只读访问，false-仅成员可访问 */
    private Boolean isPublic;

    @TableLogic
    private Integer isDeleted;
}