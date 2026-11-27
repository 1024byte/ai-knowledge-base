package com.hai.aiknowledgebase.dto.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** 评测集列表项。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalDatasetVO {

    private Long id;
    private Long kbId;
    private String kbName;
    private String name;
    private String description;
    private Integer caseCount;
    private LocalDateTime lastRunAt;
    private String lastRunState;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 评测集详情（含 Case 列表）。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Detail extends EvalDatasetVO {
        private List<EvalCaseVO> cases;
    }
}
