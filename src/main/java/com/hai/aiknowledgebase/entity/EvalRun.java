package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hai.aiknowledgebase.dto.eval.EvalMetricsSummary;
import com.hai.aiknowledgebase.dto.eval.EvalRunConfig;
import com.hai.aiknowledgebase.mapper.handler.JsonbHandlers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/** 评测运行。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "eval_run", autoResultMap = true)
public class EvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;
    private Long kbId;
    private String name;

    /** PENDING / RUNNING / SUCCESS / FAILED / CANCELED */
    private String state;

    private Integer progress;
    private Integer totalCases;
    private Integer successCount;
    private Integer failedCount;

    @TableField(value = "config_snapshot", typeHandler = JsonbHandlers.RunConfig.class)
    private EvalRunConfig configSnapshot;

    @TableField(value = "param_snapshot", typeHandler = JsonbHandlers.StringMap.class)
    private Map<String, String> paramSnapshot;

    @TableField(value = "metrics_summary", typeHandler = JsonbHandlers.MetricsSummary.class)
    private EvalMetricsSummary metricsSummary;

    private Double hitRateAt5;
    private Long avgMs;
    private Long totalMs;
    private String errorMessage;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
