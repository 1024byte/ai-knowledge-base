package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <h2>知识库文件实体</h2>
 *
 * <p>对应数据库表 {@code knowledge_document}，记录知识库下上传的文件信息、解析状态及向量化结果。</p>
 *
 * <h3>解析状态流转</h3>
 * <pre>
 * 待处理(0) → 处理中(1) → 已完成(2)
 *                  ↓
 *              失败(3)
 * </pre>
 *
 * <h3>字段说明</h3>
 * <table>
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>kbId</td><td>BIGINT</td><td>所属知识库 ID，关联 knowledge_base.id</td></tr>
 *   <tr><td>fileHash</td><td>VARCHAR(64)</td><td>文件 MD5/SHA256，用于秒传去重</td></tr>
 *   <tr><td>parsedTextUrl</td><td>VARCHAR(1000)</td><td>解析后的 .md 或 .txt 存放路径</td></tr>
 *   <tr><td>chunkCount</td><td>INT</td><td>该文件解析出的切片数量</td></tr>
 *   <tr><td>tokenCount</td><td>INT</td><td>消耗的 token 数（用于计费或统计）</td></tr>
 * </table>
 *
 * @see KnowledgeBase 关联的知识库实体
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    /** 文档标题（用户输入，可与 fileName 不同） */
    private String title;

    private String fileName;

    private String fileExt;

    private Long fileSize;

    private String fileUrl;

    private String fileHash;

    private String parsedTextUrl;

    /** 解析状态：0-待处理, 1-处理中, 2-已完成, 3-失败 */
    private Integer status;

    /** 向量化状态：NULL=未触发, 0=待向量化, 1=向量化中, 2=已完成 */
    private Integer isVectorized;

    private String errorMessage;

    private Integer chunkCount;

    private Integer tokenCount;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer isDeleted;
}