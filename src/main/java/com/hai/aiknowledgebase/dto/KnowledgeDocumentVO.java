package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <h2>知识库文件响应 VO</h2>
 *
 * <p>返回给前端的文件详情，包含文件元信息、解析状态及统计数据。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentVO {

    private Long id;
    private Long kbId;
    private String title;
    private String fileName;
    private String fileExt;
    private Long fileSize;
    private String fileUrl;
    private String fileHash;
    private String parsedTextUrl;

    /** 解析状态：0-待处理, 1-处理中, 2-已完成, 3-失败 */
    private Integer status;

    /** 向量化状态：NULL=未触发, 0=待向量化, 1=向量化中, 2=已完成, 3=失败 */
    private Integer isVectorized;

    /** 解析状态：unparsed / parsing / parsed / failed */
    private String parseStatus;

    /** 向量化状态：unvectorized / vectorizing / vectorized / failed */
    private String vectorStatus;

    private String errorMessage;
    private Integer chunkCount;
    private Integer tokenCount;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 逻辑删除标记：0-正常, 1-已删除 */
    private Integer isDeleted;
}