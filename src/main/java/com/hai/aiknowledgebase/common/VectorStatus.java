package com.hai.aiknowledgebase.common;

/**
 * <h2>向量化状态常量</h2>
 *
 * <p>统一维护「文档级」与「片段级」向量化状态值域，避免魔法数字散落在业务代码中。</p>
 *
 * <h3>文档级 is_vectorized（片段的聚合结果）</h3>
 * <ul>
 *   <li>{@code null}：未触发（从未保存过切片 / 从未向量化）</li>
 *   <li>{@link #DOC_PENDING}（0）：待向量化</li>
 *   <li>{@link #DOC_PROCESSING}（1）：向量化中</li>
 *   <li>{@link #DOC_COMPLETED}（2）：已完成</li>
 *   <li>{@link #DOC_PARTIAL}（4）：部分失败</li>
 *   <li>{@link #DOC_ALL_FAILED}（5）：全部失败</li>
 * </ul>
 *
 * <h3>片段级 vector_status</h3>
 * <ul>
 *   <li>{@link #CHUNK_PENDING}（0）：待处理</li>
 *   <li>{@link #CHUNK_PROCESSING}（1）：处理中</li>
 *   <li>{@link #CHUNK_COMPLETED}（2）：已完成</li>
 *   <li>{@link #CHUNK_FAILED}（3）：失败</li>
 * </ul>
 */
public final class VectorStatus {

    private VectorStatus() {
    }

    // ==================== 文档级 ====================

    /** 文档：待向量化 */
    public static final int DOC_PENDING = 0;
    /** 文档：向量化中 */
    public static final int DOC_PROCESSING = 1;
    /** 文档：已完成 */
    public static final int DOC_COMPLETED = 2;
    /** 文档：部分失败 */
    public static final int DOC_PARTIAL = 4;
    /** 文档：全部失败 */
    public static final int DOC_ALL_FAILED = 5;

    // ==================== 片段级 ====================

    /** 片段：待处理 */
    public static final int CHUNK_PENDING = 0;
    /** 片段：处理中 */
    public static final int CHUNK_PROCESSING = 1;
    /** 片段：已完成 */
    public static final int CHUNK_COMPLETED = 2;
    /** 片段：失败 */
    public static final int CHUNK_FAILED = 3;
}