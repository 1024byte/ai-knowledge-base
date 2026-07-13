package com.hai.aiknowledgebase.config;

import lombok.Builder;
import lombok.Data;

// 切分配置（针对不同内容类型）
@Data
@Builder
public class ChunkingConfig {
    private int minTokens;
    private int maxTokens;
    private double overlapRatio;
    private boolean enableSemantic;      // 是否启用语义切分
    private double semanticThreshold;    // 语义转折阈值，仅当 enableSemantic=true 时生效

    // 预置配置
    public static final ChunkingConfig TECHNICAL = ChunkingConfig.builder()
            .minTokens(200)
            .maxTokens(1000)
            .overlapRatio(0.1)
            .enableSemantic(false)        // 技术文档靠结构（代码块、标题），关语义
            .semanticThreshold(0.7)
            .build();

    public static final ChunkingConfig LEGAL = ChunkingConfig.builder()
            .minTokens(300)
            .maxTokens(1200)
            .overlapRatio(0.25)           // 法条需要大重叠，防止把完整条款切断
            .enableSemantic(true)         // 法律文本无标题，靠语义聚类
            .semanticThreshold(0.75)
            .build();

    public static final ChunkingConfig TABLE_HEAVY = ChunkingConfig.builder()
            .minTokens(100)
            .maxTokens(800)
            .overlapRatio(0.05)
            .enableSemantic(false)        // 表格按行切，不要语义
            .semanticThreshold(0.7)
            .build();

    public static final ChunkingConfig GENERAL = ChunkingConfig.builder()
            .minTokens(100)
            .maxTokens(512)
            .overlapRatio(0.1)
            .enableSemantic(false)        // 常规文档递归切分足够
            .semanticThreshold(0.7)
            .build();
}

