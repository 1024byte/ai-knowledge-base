package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h2>文档解析内容更新请求 DTO</h2>
 *
 * <p>前端编辑解析后的 Markdown 原文后提交的保存请求。</p>
 *
 * <h3>confirm 参数机制</h3>
 * <p>当文档已存在切片且 confirm=false 时，接口拒绝保存并返回提示，
 * 引导前端弹出确认框；用户点确认后带 confirm=true 重试，才真正执行清空切片。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentContentUpdateDTO {

    /** 编辑后的 Markdown 全文 */
    private String content;

    /** 用户确认标记：为 true 时允许清空已有切片 */
    private Boolean confirm;
}