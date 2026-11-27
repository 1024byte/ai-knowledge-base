package com.hai.aiknowledgebase.dto.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 评测运行列表项。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRunVO {

    private Long id;
    private Long datasetId;
    private String datasetName;
    private Long kbId;
    private String kbName;
    private String name;
    private String state;
    private Integer progress;
    private Integer totalCases;
    private Double hitRateAt5;
    private Long avgMs;
    private Long totalMs;
    private String createdBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
