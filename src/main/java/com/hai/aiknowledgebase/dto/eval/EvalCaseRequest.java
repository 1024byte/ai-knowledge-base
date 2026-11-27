package com.hai.aiknowledgebase.dto.eval;

import lombok.Data;

import java.util.List;

/** 新增 / 修改评测 Case 请求（修改时字段均可选）。 */
@Data
public class EvalCaseRequest {

    private String query;
    private List<Long> relevantDocumentIds;
    private List<String> tags;
    private Integer sortOrder;

    /** 批量新增请求体。 */
    @Data
    public static class Batch {
        private List<EvalCaseRequest> cases;
    }
}
