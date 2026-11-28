package com.hai.aiknowledgebase.model;

import com.hai.aiknowledgebase.service.RerankerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 精排提供者（按配置分发，{@code @Primary}）。
 *
 * <ul>
 *   <li>{@code ONNX} / 未配置：走本地 {@link RerankerService}</li>
 *   <li>{@code DASHSCOPE}：走阿里云原生 Rerank，失败回退本地</li>
 *   <li>{@code AUTO}：云端优先，失败/超时回退本地</li>
 * </ul>
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RerankProvider implements Reranker {

    private final ModelProfileResolver profileResolver;
    private final RerankerService localReranker;
    private final DashScopeReranker dashScopeReranker;

    @Override
    public boolean isEnabled() {
        return localReranker.isEnabled();
    }

    @Override
    public boolean isAvailable() {
        ModelProfile profile = safeResolve();
        if (profile != null && isCloud(profile) && profile.hasApiKey()) {
            return true;
        }
        return localReranker.isAvailable();
    }

    @Override
    public double getMinScore() {
        return localReranker.getMinScore();
    }

    @Override
    public int getMaxDocs() {
        return localReranker.getMaxDocs();
    }

    @Override
    public long getTimeoutMs() {
        return localReranker.getTimeoutMs();
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> docs, int topN) {
        return rerank(query, docs, topN, 0, 0);
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> docs, int topN, int maxDocs, long timeoutMs) {
        ModelProfile profile = safeResolve();
        if (profile == null || !isCloud(profile)) {
            return localReranker.rerank(query, docs, topN, maxDocs, timeoutMs);
        }
        int limit = maxDocs > 0 ? maxDocs : getMaxDocs();
        List<String> candidates = docs.size() > limit ? docs.subList(0, limit) : docs;
        try {
            List<RerankResult> results = dashScopeReranker.rerank(profile, query, candidates);
            return results.size() > topN ? results.subList(0, topN) : results;
        } catch (Exception e) {
            log.warn("云端精排失败，回退本地 ONNX: {}", e.getMessage());
            return localReranker.rerank(query, docs, topN, maxDocs, timeoutMs);
        }
    }

    private boolean isCloud(ModelProfile profile) {
        return profile.getProvider() == ModelProviderType.DASHSCOPE
                || profile.getProvider() == ModelProviderType.AUTO;
    }

    private ModelProfile safeResolve() {
        try {
            return profileResolver.resolve(ModelBizType.RERANK);
        } catch (Exception e) {
            log.warn("解析精排配置失败，使用本地 ONNX: {}", e.getMessage());
            return null;
        }
    }
}
