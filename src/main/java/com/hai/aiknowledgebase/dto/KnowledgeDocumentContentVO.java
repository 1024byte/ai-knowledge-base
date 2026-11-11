package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h2>文档解析内容响应 VO</h2>
 *
 * <p>返回文档解析后的 Markdown 原文，供前端"预览"解析效果。</p>
 *
 * <h3>使用场景</h3>
 * <p>用户在知识库管理页点击"预览"时，调用此接口读取已解析的文档内容并渲染。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentContentVO {

    /** 文档 ID */
    private Long documentId;

    /** 文件名（用于展示） */
    private String fileName;

    /** 解析后的 Markdown 内容 */
    private String content;
}