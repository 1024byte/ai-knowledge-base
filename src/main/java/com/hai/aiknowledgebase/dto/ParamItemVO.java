package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h2>参数列表项 VO</h2>
 *
 * <p>用于「参数管理」页面的列表展示，将参数元数据、草稿值、已应用值、生效值
 * 合并为一个视图，前端据此区分「草稿未同步 / 已应用 / 生效值」。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParamItemVO {

    // ===== 元数据 =====
    private String paramKey;

    private String paramName;

    private String paramType;

    private String defaultValue;

    private String minValue;

    private String maxValue;

    private String category;

    private String description;

    private Integer sortOrder;

    // ===== 本作用域配置 =====

    /** 本作用域已应用值（无覆盖时为 null） */
    private String appliedValue;

    /** 本作用域草稿值（无草稿时为 null） */
    private String draftValue;

    /** 生效值（KB 覆盖 &gt; 全局已应用 &gt; 元数据默认值） */
    private String effectiveValue;
}