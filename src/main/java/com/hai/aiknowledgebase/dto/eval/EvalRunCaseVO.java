package com.hai.aiknowledgebase.dto.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** 运行 Case 明细（列表不携带 resultSnapshot）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRunCaseVO {

    private Long id;
    private Long caseId;
    private String query;
    private List<String> tags;
    private List<Long> relevantDocumentIds;
    private Boolean hit;
    private Integer firstHitRank;
    private Map<String, Boolean> hitAtK;
    private Double recallAt5;
    private Double ndcgAt10;
    private Double mrr;
    private Long totalMs;
    private String rewritePath;
    private String rewrittenQuery;
    private String errorMessage;
}
