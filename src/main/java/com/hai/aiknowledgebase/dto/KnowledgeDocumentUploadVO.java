package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h2>知识库文件上传响应</h2>
 *
 * <p>文件上传接口的响应 DTO，使用前端友好的枚举字符串表示解析和向量化状态。</p>
 *
 * <h3>状态映射</h3>
 * <table>
 *   <tr><th>内部 status</th><th>parseStatus</th><th>vectorStatus</th></tr>
 *   <tr><td>0（待处理）</td><td>unparsed</td><td>unvectorized</td></tr>
 *   <tr><td>1（处理中）</td><td>parsing</td><td>unvectorized</td></tr>
 *   <tr><td>2（已完成）</td><td>parsed</td><td>vectorized</td></tr>
 *   <tr><td>3（失败）</td><td>failed</td><td>unvectorized</td></tr>
 * </table>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentUploadVO {

    private Long id;
    private String title;
    private String knowledgeBase;
    private String fileName;
    private String fileType;
    private Long fileSize;

    /** 解析状态：unparsed / parsing / parsed / failed */
    private String parseStatus;

    /** 向量化状态：unvectorized / vectorizing / vectorized / failed */
    private String vectorStatus;

    /** 创建时间，格式 YYYY-MM-DD HH:mm:ss */
    private String createdAt;
}