package com.hai.aiknowledgebase.queryrewrite.corrector;

import lombok.Builder;
import lombok.Data;

/**
 * 单条纠错明细
 */
@Data
@Builder
public class CorrectionDetail {

    /** 原始词 */
    private String original;

    /** 纠正后的词 */
    private String corrected;

    /** 编辑距离 */
    private int editDistance;

    /** 纠错原因（同音字/形近字/编辑距离/LLM语义） */
    private String reason;
}