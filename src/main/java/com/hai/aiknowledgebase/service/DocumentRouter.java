package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.common.ContentAnalyzer;
import com.hai.aiknowledgebase.common.ContentCategory;
import com.hai.aiknowledgebase.common.CustomDocument;
import com.hai.aiknowledgebase.config.ChunkingConfig;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
@Component
public class DocumentRouter {


    private final TokenCountEstimator tokenEstimator;
    private final EmbeddingModel embeddingModel;          // 可为 null（如果不开语义）
    private final double defaultSemanticThreshold = 0.75;

    // 缓存已创建的 Chunker 实例（按配置摘要缓存，避免重复创建）
    private final Map<String, MarkdownDocumentChunker> chunkerCache = new ConcurrentHashMap<>();

    private final ContentAnalyzer contentAnalyzer = new ContentAnalyzer();

    /**
     * 路由入口：根据文档内容和格式，选择最优切分策略
     *
     * @param customDocument 统一文档对象（包含格式、内容、元数据）
     * @return 切片结果
     */
    public List<MarkdownDocumentChunker.Chunk> route(CustomDocument customDocument) {
        // ============ 第一步：格式特殊通道 ============
        if (customDocument.getFormat() == CustomDocument.Format.EXCEL ||
                customDocument.getFormat() == CustomDocument.Format.CSV) {
            log.info("文档 [{}] 为表格格式，路由到 Excel 行切分器", customDocument.getFileName());
            return chunkExcelByRows(customDocument);
        }

        // ============ 第二步：提取文本内容（PDF/DOCX/MD/TXT/OCR后的图片） ============
        String content = customDocument.getContent();
        if (content == null || content.isBlank()) {
            log.warn("文档 [{}] 内容为空，跳过", customDocument.getFileName());
            return List.of();
        }

        // ============ 第三步：内容特征分析 ============
        ContentCategory category = contentAnalyzer.analyze(content);
        log.info("文档 [{}] 分类为: {}", customDocument.getFileName(), category);

        // ============ 第四步：根据分类获取配置 ============
        ChunkingConfig config = getConfigForCategory(category);

        // ============ 第五步：获取或创建对应的 Chunker（带缓存） ============
        String cacheKey = buildCacheKey(config);
        MarkdownDocumentChunker chunker = chunkerCache.computeIfAbsent(cacheKey, key -> {
            log.debug("创建新的 Chunker 实例: {}", cacheKey);
            // 注意：如果 config.enableSemantic 为 false，传入 null 避免 Embedding 调用
            EmbeddingModel modelToUse = config.isEnableSemantic() ? embeddingModel : null;
            return new MarkdownDocumentChunker(
                    config.getMinTokens(),
                    config.getMaxTokens(),
                    config.getOverlapRatio(),
                    tokenEstimator,
                    modelToUse,
                    config.getSemanticThreshold()
            );
        });

        // ============ 第六步：执行切分 ============
        List<MarkdownDocumentChunker.Chunk> chunks = chunker.chunk(content);
        log.info("文档 [{}] 切分完成: {} 个 chunk", customDocument.getFileName(), chunks.size());
        return chunks;
    }

    /**
     * 根据内容分类获取切分配置
     */
    private ChunkingConfig getConfigForCategory(ContentCategory category) {
        switch (category) {
            case TECHNICAL:
                return ChunkingConfig.TECHNICAL;
            case LEGAL:
                return ChunkingConfig.LEGAL;
            case TABLE_HEAVY:
                return ChunkingConfig.TABLE_HEAVY;
            case GENERAL:
            default:
                return ChunkingConfig.GENERAL;
        }
    }

    /**
     * 构建缓存 Key（用配置的核心参数拼接）
     */
    private String buildCacheKey(ChunkingConfig config) {
        return String.format("%d_%d_%.2f_%b_%.2f",
                config.getMinTokens(),
                config.getMaxTokens(),
                config.getOverlapRatio(),
                config.isEnableSemantic(),
                config.getSemanticThreshold());
    }

    // ==================== Excel/CSV 特殊切分逻辑 ====================

    /**
     * Excel/CSV 按行切分：每行数据 + 表头拼接为一个 Chunk
     * <p>
     * 注意：这里假设 document.getContent() 已经是解析后的 Markdown 表格格式
     * 或者直接是 CSV 文本。
     */
    private List<MarkdownDocumentChunker.Chunk> chunkExcelByRows(CustomDocument customDocument) {
        String content = customDocument.getContent();
        if (content == null || content.isBlank()) return List.of();

        // 如果内容是 CSV 格式（逗号分隔），转为按行切
        String[] lines = content.split("\n");
        if (lines.length < 2) {
            // 不足两行，退回默认切分
            log.warn("Excel 内容行数过少，降级为默认切分");
            return routeToDefaultChunker(customDocument);
        }

        // 提取表头（第一行）
        String header = lines[0].strip();
        List<MarkdownDocumentChunker.Chunk> chunks = new ArrayList<>();

        for (int i = 1; i < lines.length; i++) {
            String row = lines[i].strip();
            if (row.isBlank()) continue;

            // 拼接：表头 + 当前行
            String chunkText = header + "\n" + row;
            int tokenCount = tokenEstimator.estimateTokenCountInText(chunkText);
            chunks.add(new MarkdownDocumentChunker.Chunk(chunkText, tokenCount, "ExcelRow"));
        }

        log.info("Excel 按行切分完成: {} 行", chunks.size());
        return chunks;
    }

    /**
     * 降级方案：如果特殊通道失败，走默认 GENERAL 配置
     */
    private List<MarkdownDocumentChunker.Chunk> routeToDefaultChunker(CustomDocument customDocument) {
        ChunkingConfig config = ChunkingConfig.GENERAL;
        String cacheKey = buildCacheKey(config);
        MarkdownDocumentChunker chunker = chunkerCache.computeIfAbsent(cacheKey, key ->
                new MarkdownDocumentChunker(
                        config.getMinTokens(),
                        config.getMaxTokens(),
                        config.getOverlapRatio(),
                        tokenEstimator,
                        null,  // 降级场景关语义
                        config.getSemanticThreshold()
                )
        );
        return chunker.chunk(customDocument.getContent());
    }
}