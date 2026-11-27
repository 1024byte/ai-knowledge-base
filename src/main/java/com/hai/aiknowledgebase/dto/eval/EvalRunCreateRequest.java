package com.hai.aiknowledgebase.dto.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建评测运行请求。
 *
 * <p>{@code enableRewrite} 由前端展开为布尔值（"跟随参数管理"在前端已解析为当前生效值），
 * 后端不做三态语义。{@code threshold} / {@code rrfK} 为可选，缺省走召回测试默认值。</p>
 */
@Data
public class EvalRunCreateRequest {

    private Long datasetId;
    private String name;
    private String strategy;
    private Integer topK;
    private Double threshold;
    private Integer rrfK;
    private Boolean enableRerank;
    private String rerankStage;
    private Double rerankThreshold;
    private Integer rerankMaxDocs;
    private Integer candidateMultiplier;
    private Integer maxCandidates;
    private Integer topN;
    private Boolean enableRewrite;
    private List<String> excludeKeywords = new ArrayList<>();
}
