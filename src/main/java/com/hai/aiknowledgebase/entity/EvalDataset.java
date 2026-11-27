package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 评测集。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("eval_dataset")
public class EvalDataset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;
    private String name;
    private String description;
    private Integer caseCount;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer isDeleted;
}
