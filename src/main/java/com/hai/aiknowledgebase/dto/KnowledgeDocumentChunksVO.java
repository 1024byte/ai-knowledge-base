package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * <h2>文档切片预览响应 VO</h2>
 *
 * <p>返回文档切片后的片段列表，供前端预览切片效果。</p>
 *
 * <h3>使用场景</h3>
 * <p>前端在向量化前调用此接口预览切片结果，确认切片质量后再触发向量化存储。</p>
 *
 * @see com.hai.aiknowledgebase.service.MarkdownDocumentChunker.Chunk 切片引擎输出
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentChunksVO {

    /** 文档 ID */
    private Long documentId;

    /** 文件名（用于展示和识别） */
    private String fileName;

    /** 切片总数 */
    private int totalChunks;

    /** 切片列表 */
    private List<ChunkItem> chunks;

    /**
     * <h3>单个切片项</h3>
     *
     * <p>对应 {@code MarkdownDocumentChunker.Chunk}，附加 {@code index} 顺序号。</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkItem {

        /** 切片序号（从 0 开始） */
        private int index;

        /** 切片文本（含上下文前缀如 "**上下文**: 第一章 > 1.1\n\n..."） */
        private String text;

        /** Token 估算值 */
        private int tokenCount;

        /** 层级路径（如 "第一章 > 1.1 概述"） */
        private String contextPrefix;

        /**
         * <h4>附加元数据</h4>
         * <table>
         *   <tr><th>Key</th><th>说明</th></tr>
         *   <tr><td>parent_text</td><td>父级段落全文</td></tr>
         *   <tr><td>parent_start</td><td>父级起始 chunk 索引</td></tr>
         *   <tr><td>parent_end</td><td>父级结束 chunk 索引</td></tr>
         *   <tr><td>content_type</td><td>内容类型（code/table/text）</td></tr>
         * </table>
         */
        private Map<String, String> metadata;
    }
}