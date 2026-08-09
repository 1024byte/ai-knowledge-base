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

    // 父上下文窗口大小：每个 chunk 的 parent_text 包含自身 + 前后各 PARENT_WINDOW 个 chunk
    public static final int PARENT_WINDOW = 2;

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

    // 试卷/试题文档：英文段落较长，需要更大 maxTokens 保证语义完整
    public static final ChunkingConfig EXAM_PAPER = ChunkingConfig.builder()
            .minTokens(200)
            .maxTokens(800)
            .overlapRatio(0.15)
            .enableSemantic(false)        // 试题依赖结构（题目编号、选项），关语义
            .semanticThreshold(0.7)
            .build();
}