package com.hai.aiknowledgebase.dto.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/** 评测运行详情（前端轮询该接口）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRunDetailVO {

    private Long id;
    private Long datasetId;
    private String datasetName;
    private Long kbId;
    private String kbName;
    private String name;
    private String state;
    private Integer progress;
    private Integer totalCases;
    private Integer successCount;
    private Integer failedCount;
    private EvalRunConfig configSnapshot;
    private Map<String, String> paramSnapshot;
    private EvalMetricsSummary metricsSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private String errorMessage;
}
