package com.hai.aiknowledgebase.dto.eval;

import com.hai.aiknowledgebase.dto.RecallTestResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 单条 Case 详情，含完整召回测试响应（前端复用召回测试结果区组件渲染）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRunCaseDetailVO {

    private Long id;
    private Long caseId;
    private String query;
    private List<String> tags;
    private List<Long> relevantDocumentIds;
    private Boolean hit;
    private Integer firstHitRank;
    private java.util.Map<String, Boolean> hitAtK;
    private Double recallAt5;
    private Double ndcgAt10;
    private Double mrr;
    private Long totalMs;
    private String rewritePath;
    private String rewrittenQuery;
    private String errorMessage;

    private List<Long> retrievedDocumentIds;
    private List<Double> retrievedScores;

    /** 完整 RecallTestResponse，结构与召回测试接口一致。 */
    private RecallTestResponse resultSnapshot;
}
