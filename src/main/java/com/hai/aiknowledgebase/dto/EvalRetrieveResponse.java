package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRetrieveResponse {

    private String query;
    private String rewrittenQuery;
    private String rewritePath;
    private String rewriteStrategy;
    private double rewriteConfidence;
    private List<String> expandKeywords;
    private List<String> excludeKeywords;
    private StageResult hybrid;
    private ExcludeFilterResult excludeFilter;
    private StageResult rerank;
    private List<RetrievedDoc> finalRanked;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievedDoc {
        private String text;
        private double score;
        private String source;
        private String documentId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageResult {
        private List<RetrievedDoc> results;
        private int count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExcludeFilterResult {
        private int beforeCount;
        private int afterCount;
        private List<Integer> removedIndices;
    }
}