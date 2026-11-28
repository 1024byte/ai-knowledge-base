package com.hai.aiknowledgebase.model;

import com.hai.aiknowledgebase.entity.ModelConfig;
import com.hai.aiknowledgebase.service.RerankerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型连通性测试：发一条最小请求验证 provider / baseUrl / model / key 组合是否可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelConnectivityTester {

    private final ModelConfigService modelConfigService;
    private final ModelClientFactory clientFactory;
    private final DashScopeReranker dashScopeReranker;
    private final RerankerService localReranker;

    /** CHAT 配置解析不到 Key 时的 bootstrap 兜底 */
    @Value("${deepseek.api-key:}")
    private String bootstrapChatApiKey;

    /**
     * 测试指定配置。
     *
     * @return success / latencyMs / message
     */
    public Map<String, Object> test(Long id) {
        ModelConfig config = modelConfigService.getById(id);
        Map<String, Object> result = new LinkedHashMap<>();
        if (config == null) {
            result.put("success", false);
            result.put("message", "模型配置不存在: " + id);
            return result;
        }
        ModelBizType type = ModelBizType.of(config.getBizType());
        String fallbackKey = type == ModelBizType.CHAT ? bootstrapChatApiKey : null;
        ModelProfile profile = modelConfigService.toProfile(config, fallbackKey);
        if (profile == null || profile.getProvider() == null) {
            result.put("success", false);
            result.put("message", "配置解析失败");
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            String message = switch (profile.getProvider()) {
                case ONNX -> "本地 ONNX 模型" + (localReranker.isAvailable() ? "已加载" : "未加载（检查 reranker.enabled 与模型文件）");
                case DASHSCOPE, AUTO -> testCloudRerank(profile);
                case OPENAI_COMPAT, OLLAMA -> testChat(profile);
            };
            boolean success = profile.getProvider() != ModelProviderType.ONNX || localReranker.isAvailable();
            result.put("success", success);
            result.put("message", message);
        } catch (Exception e) {
            log.warn("模型连通性测试失败: id={}, err={}", id, e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        result.put("latencyMs", System.currentTimeMillis() - start);
        return result;
    }

    private String testChat(ModelProfile profile) {
        String reply = clientFactory.buildChat(profile).chat("ping，请只回复 pong");
        return "连通正常，返回: " + abbreviate(reply);
    }

    private String testCloudRerank(ModelProfile profile) throws Exception {
        List<RerankResult> results = dashScopeReranker.rerank(
                profile, "连通性测试", List.of("这是一段测试文档", "这是另一段测试文档"));
        return "连通正常，返回 " + results.size() + " 条精排结果";
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 100 ? trimmed : trimmed.substring(0, 100) + "...";
    }
}
