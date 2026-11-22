package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <h2>参数操作历史实体</h2>
 *
 * <p>对应数据库表 {@code param_history}，记录参数的每次变更（保存 / 应用 / 回滚 / 重置），
 * 用于审计追溯。</p>
 *
 * <h3>操作类型</h3>
 * <pre>
 * SAVE     保存草稿
 * APPLY    应用生效
 * ROLLBACK 回退到全局默认
 * RESET    清空重置
 * </pre>
 */
@Data
@TableName("param_history")
public class ParamHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String paramKey;

    /** 作用域：GLOBAL / KB */
    private String scopeType;

    private Long kbId;

    /** 变更前值（可空） */
    private String oldValue;

    /** 变更后值（可空） */
    private String newValue;

    /** 操作类型：SAVE / APPLY / ROLLBACK / RESET */
    private String action;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operateTime;
}