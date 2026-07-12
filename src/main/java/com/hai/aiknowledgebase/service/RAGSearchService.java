package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.SearchResult;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.ContainsString;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 检索服务（适配 PostgreSQL + pgvector）
 * 支持：
 * 1. 按文件名精确/模糊检索（仅当用户明确指定时）
 * 2. 普通语义检索（默认）
 * 3. 双重检索（全局+指定文件合并），保证召回率
 *
 * @author Hai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGSearchService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final OpenAiChatModel chatModel;

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_WHEN_FILTERED = 20;

    // ======================== 统一检索入口（供 ChatService 调用） ========================

    /**
     * 检索相关片段（支持文件名过滤，但不会误判普通短查询）
     * 实现双重检索：全局语义检索 + 文件名过滤检索（若有指定），结果合并去重。
     *
     * @param userQuery 用户原始问题
     * @param topK      期望返回的最大片段数（普通检索使用该值，过滤检索内部放大）
     * @return 片段列表（可能为空）
     */
    public List<TextSegment> retrieveSegments(String userQuery, int topK) {
        log.info("开始检索，用户查询: {}", userQuery);

        // 1. 总是执行普通语义检索（保证基础召回）
        List<TextSegment> allResults = new ArrayList<>(semanticSearch(userQuery, topK));
        log.info("普通语义检索召回 {} 个片段", allResults.size());

        // 2. 检测是否明确指定了文件名（只有在匹配到特定模式时才提取，不会误判）
        String fileNameKeyword = extractFileName(userQuery);
        if (fileNameKeyword != null) {
            log.info("检测到文件名关键词: {}，执行辅助过滤检索", fileNameKeyword);
            String contentQuery = extractContentQuery(userQuery, fileNameKeyword);

            // 执行带文件名的过滤检索（内部 topK 放大，以覆盖更多片段）
            List<TextSegment> filtered = searchWithFileNameFilter(fileNameKeyword, contentQuery, userQuery, MAX_RESULTS_WHEN_FILTERED);

            // 去重合并（根据 TextSegment 的 text 内容去重）
            for (TextSegment seg : filtered) {
                if (allResults.stream().noneMatch(existing -> existing.text().equals(seg.text()))) {
                    allResults.add(seg);
                }
            }

            // 如果过滤结果为空，尝试去掉扩展名再搜一次
            if (filtered.isEmpty()) {
                String noExt = removeExtension(fileNameKeyword);
                if (!noExt.equals(fileNameKeyword)) {
                    log.info("尝试不带扩展名检索: {}", noExt);
                    List<TextSegment> retry = searchWithFileNameFilter(noExt, contentQuery, userQuery, MAX_RESULTS_WHEN_FILTERED);
                    for (TextSegment seg : retry) {
                        if (allResults.stream().noneMatch(existing -> existing.text().equals(seg.text()))) {
                            allResults.add(seg);
                        }
                    }
                }
            }
        }

        // 如果结果过多，可截断（保留最相关的 topK*2 或全部交由调用方处理）
        // 这里返回全部，由调用方决定使用多少
        log.info("最终返回 {} 个片段", allResults.size());
        return allResults;
    }

    // ======================== 核心检索方法 ========================

    /**
     * 普通语义检索（无过滤）
     */
    private List<TextSegment> semanticSearch(String userQuery, int topK) {
        Embedding queryEmbedding = embeddingModel.embed(userQuery).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                // 不设置 minScore，让所有结果按相似度排序返回
                .build();
        return embeddingStore.search(request).matches().stream()
                .map(EmbeddingMatch::embedded)
                .collect(Collectors.toList());
    }

    /**
     * 带文件名过滤的语义检索（使用 ContainsString 模糊匹配）
     */
    private List<TextSegment> searchWithFileNameFilter(String fileNameKeyword, String contentQuery, String originalQuery, int maxResults) {
        // 构建文件名过滤器（元数据 key 为 "source"）
        Filter filter = new ContainsString("source", fileNameKeyword);

        // 如果提取的内容查询为空，则使用原始查询
        String searchQuery = contentQuery.trim().isEmpty() ? originalQuery : contentQuery;

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(searchQuery).content())
                .maxResults(maxResults)
                .filter(filter)
                // minScore 已移除，避免因阈值过高导致召回不足
                .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();
        log.info("文件名过滤检索（{}）召回 {} 个片段", fileNameKeyword, matches.size());
        return matches.stream()
                .map(EmbeddingMatch::embedded)
                .collect(Collectors.toList());
    }

    // ======================== 文件名提取（已修复误判问题） ========================

    /**
     * 从用户查询中提取文件名关键词。
     * <p>
     * 只有在用户明确使用模式（如“只看xx里的”、“打开xx文档”）或包含扩展名时才提取，
     * 不再将短查询或无动作词的查询当作文件名，避免误判。
     */
    private String extractFileName(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }

        String trimmed = query.trim();

        // 1. 尝试提取带扩展名的精确文件名（支持中文、英文、数字、下划线、连字符）
        Pattern exactFileNamePattern = Pattern.compile("([\\w\\-_\u4e00-\u9fa5]+\\.(md|pdf|docx|txt|pptx|xlsx|doc|ppt|xls))");
        var exactMatcher = exactFileNamePattern.matcher(trimmed);
        if (exactMatcher.find()) {
            String fileName = exactMatcher.group(1);
            log.debug("精确匹配到文件名: {}", fileName);
            return fileName;
        }

        // 2. 智能解析常见查询模式（明确指定文件）
        // 模式1: "只看[文件名]里的[内容]"
        Pattern pattern1 = Pattern.compile("只看\\s*([\\w\\-_\u4e00-\u9fa5]+(?:\\.\\w+)?)\\s*里的");
        // 模式2: "打开[文件名]文档"
        Pattern pattern2 = Pattern.compile("打开\\s*([\\w\\-_\u4e00-\u9fa5]+(?:\\.\\w+)?)\\s*(?:文档|文件)");
        // 模式3: "查看[文件名]"
        Pattern pattern3 = Pattern.compile("查看\\s*([\\w\\-_\u4e00-\u9fa5]+(?:\\.\\w+)?)");
        // 模式4: "[文件名]中的[内容]"
        Pattern pattern4 = Pattern.compile("([\\w\\-_\u4e00-\u9fa5]+(?:\\.\\w+)?)\\s*中的");

        String[] candidates = {
                extractWithPattern(trimmed, pattern1),
                extractWithPattern(trimmed, pattern2),
                extractWithPattern(trimmed, pattern3),
                extractWithPattern(trimmed, pattern4)
        };
        for (String fileName : candidates) {
            if (fileName != null && !fileName.isEmpty()) {
                log.debug("智能解析到文件名: {}", fileName);
                return fileName;
            }
        }

        // 3. 启发式规则已移除 —— 避免把“理论部分”等普通短查询误判为文件名
        // 只有上述明确模式才视为文件指定

        // 4. 尝试清理前后缀提取（但仅当包含“文档”“文件”等词时才考虑，此处放宽但不再误判）
        // 注意：以下清理仅当字符串本身明显包含文件名特征时才启用，但我们保留保守。
        // 为了安全，这里不再额外提取，直接返回 null。
        // 如果后续有需求，可增加更精确的规则，但需确保不误伤。

        log.debug("未检测到明确的文件名关键词: {}", trimmed);
        return null;
    }

    /**
     * 使用正则提取文件名，若匹配则返回第一个捕获组
     */
    private String extractWithPattern(String query, Pattern pattern) {
        var matcher = pattern.matcher(query);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * 从查询中移除文件名关键词，提取纯内容部分（供向量化使用）
     */
    private String extractContentQuery(String userQuery, String fileNameKeyword) {
        String query = userQuery;
        query = query.replace(fileNameKeyword, "").trim();

        // 移除常见模式前缀
        String[] patternsToRemove = {
                "只看", "打开", "查看", "查找", "搜索", "查询",
                "里的", "中的", "里面", "文档", "文件", "内容"
        };
        for (String pattern : patternsToRemove) {
            query = query.replace(pattern, "").trim();
        }
        query = query.replaceAll("[，。；;、,]", " ").trim();
        query = query.replaceAll("\\s+", " ");
        return query.isEmpty() ? userQuery : query;
    }

    /**
     * 移除文件扩展名
     */
    private String removeExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(0, lastDotIndex);
        }
        return fileName;
    }

    // ======================== 构建上下文与生成答案 ========================

    /**
     * 构建上下文（供外部使用，也可内部调用）
     */
    public String buildContext(List<TextSegment> segments, String fileName) {
        StringBuilder sb = new StringBuilder();
        if (fileName != null) {
            sb.append("以下内容来自文件: ").append(fileName).append("\n\n");
        }
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            sb.append("【片段").append(i + 1).append("】\n");
            sb.append(seg.text()).append("\n\n");
        }
        return sb.toString();
    }

    // 向后兼容的重载
    private String buildContext(List<TextSegment> segments) {
        return buildContext(segments, null);
    }

    /**
     * 生成答案（供外部调用，也可内部使用）
     */
    public String generateAnswer(String userQuery, String context, boolean isFileNameSearch) {
        String prompt;
        if (isFileNameSearch) {
            prompt = String.format(
                    "用户正在查找特定文件的内容。请基于以下文档片段回答问题，如果无法从片段中找到答案，请明确告知。\n\n" +
                            "文档内容：\n%s\n\n" +
                            "问题：%s\n" +
                            "回答：",
                    context, userQuery
            );
        } else {
            prompt = String.format(
                    "基于以下文档片段回答问题。如果无法从片段中找到答案，请明确告知。\n\n" +
                            "文档内容：\n%s\n\n" +
                            "问题：%s\n" +
                            "回答：",
                    context, userQuery
            );
        }
        return chatModel.chat(prompt);
    }

    // 向后兼容重载
    private String generateAnswer(String userQuery, String context) {
        return generateAnswer(userQuery, context, false);
    }

    // ======================== 保持原有其他接口（如 searchAndAnswer 等） ========================
    // 下面的方法若原项目中未使用，可保留或删除，此处保留以供兼容

    public String searchAndAnswer(String userQuery) {
        List<TextSegment> segments = retrieveSegments(userQuery, DEFAULT_MAX_RESULTS);
        if (segments.isEmpty()) {
            return "未找到相关信息，请尝试更换关键词或指定文件名进行搜索。";
        }
        String fileName = extractFileName(userQuery); // 可能为null
        String context = buildContext(segments, fileName);
        return generateAnswer(userQuery, context, fileName != null);
    }

    public List<SearchResult> search(String query, int topK) {
        // 此方法为之前遗留，与现有逻辑不冲突，可保留
        List<TextSegment> segments = retrieveSegments(query, topK);
        return segments.stream()
                .map(seg -> {
                    String source = seg.metadata().getString("source");
                    return new SearchResult(
                            seg.text(),
                            0.0, // 原接口需要score，但此处无法获取，可改为0或查找匹配
                            source != null ? source : "未知来源"
                    );
                })
                .collect(Collectors.toList());
    }

}