package com.hai.aiknowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * <h2>批量快速切片响应 DTO</h2>
 *
 * <p>批量自动切片接口的返回结果，统计总数、成功数和失败明细。</p>
 *
 * <h3>响应示例</h3>
 * <pre>
 * {
 *   "total": 5,
 *   "success": 3,
 *   "failures": [
 *     {"id": 3, "message": "文档未完成解析，无法切片"},
 *     {"id": 5, "message": "解析文件已丢失，请重新解析"}
 *   ]
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchChunkResult {

    /** 请求的总文档数 */
    private int total;

    /** 成功切片的文档数 */
    private int success;

    /** 失败明细（成功时为空列表） */
    private List<Failure> failures = new ArrayList<>();

    /**
     * <h3>批量切片失败明细</h3>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Failure {
        /** 文档 ID */
        private Long id;
        /** 失败原因 */
        private String message;
    }
}