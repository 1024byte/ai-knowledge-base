package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hai.aiknowledgebase.mapper.handler.JsonbHandlers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** 评测 Case（含标注的相关文档）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "eval_case", autoResultMap = true)
public class EvalCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;
    private String query;

    @TableField(value = "relevant_document_ids", typeHandler = JsonbHandlers.LongList.class)
    private List<Long> relevantDocumentIds;

    @TableField(value = "tags", typeHandler = JsonbHandlers.StringList.class)
    private List<String> tags;

    private Integer sortOrder;
    private LocalDateTime createdAt;
    private Integer isDeleted;
}
