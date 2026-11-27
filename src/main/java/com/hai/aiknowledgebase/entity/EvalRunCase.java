package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hai.aiknowledgebase.dto.RecallTestResponse;
import com.hai.aiknowledgebase.mapper.handler.JsonbHandlers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 评测运行明细。
 *
 * <p>query / tags / relevantDocumentIds 为执行时快照，
 * 数据集后续修改不影响历史报告。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "eval_run_case", autoResultMap = true)
public class EvalRunCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private Long caseId;
    private String query;

    @TableField(value = "tags", typeHandler = JsonbHandlers.StringList.class)
    private List<String> tags;

    @TableField(value = "relevant_document_ids", typeHandler = JsonbHandlers.LongList.class)
    private List<Long> relevantDocumentIds;

    private Integer sortOrder;

    /** 首个命中排名（1 起，按 chunk 位置），NULL = 未命中 */
    private Integer firstHitRank;

    @TableField(value = "hit_at_k", typeHandler = JsonbHandlers.BooleanMap.class)
    private Map<String, Boolean> hitAtK;

    private Double recallAt5;
    private Double ndcgAt10;
    private Double mrr;
    private Long totalMs;
    private String rewritePath;
    private String rewrittenQuery;

    @TableField(value = "retrieved_document_ids", typeHandler = JsonbHandlers.LongList.class)
    private List<Long> retrievedDocumentIds;

    @TableField(value = "retrieved_scores", typeHandler = JsonbHandlers.DoubleList.class)
    private List<Double> retrievedScores;

    @TableField(value = "result_snapshot", typeHandler = JsonbHandlers.RecallSnapshot.class)
    private RecallTestResponse resultSnapshot;

    private String errorMessage;
    private LocalDateTime createdAt;
}
