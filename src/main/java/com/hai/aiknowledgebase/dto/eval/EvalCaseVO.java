package com.hai.aiknowledgebase.dto.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** 评测 Case。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseVO {

    private Long id;
    private String query;
    private List<Long> relevantDocumentIds;
    private List<DocBrief> relevantDocuments;
    private List<String> tags;
    private Integer sortOrder;
    private LocalDateTime createdAt;

    /** 相关文档简要信息（前端展示文档名）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocBrief {
        private Long id;
        private String name;
    }
}
