package com.hai.aiknowledgebase.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hai.aiknowledgebase.service.ParamValueProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云 DashScope 原生 Rerank 客户端。
 *
 * <p>调用 {@code text-rerank} 接口（默认模型 {@code gte-rerank-v2}）。
 * 失败时抛出异常，由 {@link RerankProvider} 决定是否回退本地 ONNX。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeReranker {

    private static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    private static final String DEFAULT_MODEL = "gte-rerank-v2";
    private static final int DEFAULT_TIMEOUT_MS = 15000;
    private static final int DEFAULT_DOC_MAX_CHARS = 512;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ParamValueProvider paramValueProvider;

    /**
     * 调用云端精排。
     *
     * @param profile 运行时配置（需含 apiKey / baseUrl / modelName）
     * @param query   查询
     * @param docs    候选文档
     * @return 按分数降序的结果
     */
    public List<RerankResult> rerank(ModelProfile profile, String query, List<String> docs) throws Exception {
        if (!profile.hasApiKey()) {
            throw new IllegalStateException("DashScope Rerank 未配置 API Key");
        }
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        String endpoint = resolveEndpoint(profile.getBaseUrl());
        String model = isBlank(profile.getModelName()) ? DEFAULT_MODEL : profile.getModelName();

        // 云端精排按 token 计费，送入前按 rerank.docMaxChars 截断单条文本（与本地 ONNX 口径一致）
        int docMaxChars = paramValueProvider.getInt("rerank.docMaxChars", DEFAULT_DOC_MAX_CHARS);
        List<String> payloadDocs = new ArrayList<>(docs.size());
        for (String doc : docs) {
            payloadDocs.add(truncate(doc, docMaxChars));
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", payloadDocs);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("return_documents", false);
        parameters.put("top_n", docs.size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(profile.getTimeoutMs() != null && profile.getTimeoutMs() > 0
                        ? profile.getTimeoutMs() : DEFAULT_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + profile.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long cost = System.currentTimeMillis() - start;

        if (response.statusCode() != 200) {
            String apiKey = profile.getApiKey();
            log.warn("DashScope Rerank 调用失败: status={}, endpoint={}, model={}, key(len={}, hasQuote={}, hasWhitespace={}), docs={}, body={}",
                    response.statusCode(), endpoint, model,
                    apiKey == null ? 0 : apiKey.length(),
                    apiKey != null && apiKey.indexOf('"') >= 0,
                    apiKey != null && !apiKey.equals(apiKey.trim()),
                    docs.size(), abbreviate(response.body()));
            throw new IllegalStateException("DashScope Rerank 调用失败: HTTP " + response.statusCode()
                    + ", body=" + abbreviate(response.body()));
        }
        JsonNode root = objectMapper.readTree(response.body());
        int totalTokens = root.path("usage").path("total_tokens").asInt(-1);
        log.info("DashScope Rerank 完成: model={}, docs={}, docMaxChars={}, totalTokens={}, cost={}ms",
                model, docs.size(), docMaxChars, totalTokens, cost);
        return parseResults(root, docs);
    }

    private List<RerankResult> parseResults(JsonNode root, List<String> docs) {
        JsonNode results = root.path("output").path("results");
        List<RerankResult> reranked = new ArrayList<>();
        for (JsonNode node : results) {
            int index = node.path("index").asInt(-1);
            if (index < 0 || index >= docs.size()) {
                continue;
            }
            double score = node.path("relevance_score").asDouble(0.0);
            reranked.add(new RerankResult(index, docs.get(index), score));
        }
        reranked.sort(Comparator.comparingDouble(RerankResult::getScore).reversed());
        return reranked;
    }

    private String resolveEndpoint(String baseUrl) {
        if (isBlank(baseUrl)) {
            return DEFAULT_ENDPOINT;
        }
        String url = baseUrl.trim();
        if (url.contains("/services/rerank")) {
            return url;
        }
        return url.endsWith("/") ? url + "api/v1/services/rerank/text-rerank/text-rerank"
                : url + "/api/v1/services/rerank/text-rerank/text-rerank";
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    /** 按字符数截断单条文档；maxChars ≤ 0 表示不截断。 */
    private static String truncate(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }
}
