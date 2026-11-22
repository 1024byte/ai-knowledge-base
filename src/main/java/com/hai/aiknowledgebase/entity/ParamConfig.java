package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <h2>参数配置值实体</h2>
 *
 * <p>对应数据库表 {@code param_config}，记录某一参数在「全局」或「知识库级」作用域下的
 * 配置值及其状态（草稿 / 已应用）。</p>
 *
 * <h3>状态流转</h3>
 * <pre>
 * save  → DRAFT（草稿，可反复覆盖）
 * apply → APPLIED（生产生效，同作用域唯一）
 * rollback → 回退到全局默认（删除 KB 级覆盖）
 * reset → 清空（删除草稿/覆盖，回到全局默认兜底）
 * </pre>
 *
 * <h3>字段说明</h3>
 * <table>
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>scopeType</td><td>VARCHAR(16)</td><td>GLOBAL 全局 / KB 知识库级覆盖</td></tr>
 *   <tr><td>kbId</td><td>BIGINT</td><td>KB 作用域时的知识库 ID，GLOBAL 为 NULL</td></tr>
 *   <tr><td>state</td><td>VARCHAR(16)</td><td>DRAFT 草稿 / APPLIED 已应用</td></tr>
 * </table>
 *
 * <p><b>命名说明</b>：状态列命名为 {@code state} 而非 {@code status}，
 * 是因为项目 MyBatis-Plus 全局配置了 {@code logic-delete-field: status}，
 * 若命名 {@code status} 会被当作逻辑删除字段（active/deleted 语义），
 * 与 DRAFT/APPLIED 语义冲突。</p>
 */
@Data
@TableName("param_config")
public class ParamConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String paramKey;

    /** 作用域：GLOBAL 全局 / KB 知识库级覆盖 */
    private String scopeType;

    /** KB 作用域时的知识库 ID，GLOBAL 时为 NULL */
    private Long kbId;

    /** 参数值（统一字符串存储，读取时按元数据类型转换） */
    private String paramValue;

    /** 状态：DRAFT 草稿 / APPLIED 已应用 */
    private String state;

    private Long createdBy;

    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}