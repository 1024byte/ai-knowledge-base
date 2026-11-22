package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <h2>参数注册表实体</h2>
 *
 * <p>对应数据库表 {@code param_metadata}，承载检索调优参数的值域元数据
 * （类型 / 默认值 / 取值范围），后端在 save/apply 时据此强校验，避免把校验范围
 * 写死在代码 if-else 里。</p>
 *
 * <h3>字段说明</h3>
 * <table>
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>paramKey</td><td>VARCHAR(64)</td><td>参数键，如 retrieval.vectorMinScore</td></tr>
 *   <tr><td>paramType</td><td>VARCHAR(16)</td><td>INT / DOUBLE / BOOLEAN / STRING</td></tr>
 *   <tr><td>defaultValue</td><td>VARCHAR(255)</td><td>默认值（统一字符串存储）</td></tr>
 *   <tr><td>minValue / maxValue</td><td>VARCHAR(255)</td><td>数值型参数的取值范围</td></tr>
 * </table>
 */
@Data
@TableName("param_metadata")
public class ParamMetadata {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String paramKey;

    private String paramName;

    private String paramType;

    private String defaultValue;

    private String minValue;

    private String maxValue;

    private String category;

    private String description;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}