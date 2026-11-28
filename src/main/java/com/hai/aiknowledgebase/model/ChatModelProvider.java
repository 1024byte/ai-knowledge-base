package com.hai.aiknowledgebase.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 对话 / 改写模型提供者（热切换入口）。
 *
 * <p>消费方每次调用时向本类取客户端，而不是注入具体 Bean，从而支持运行时切换：
 * 优先使用 {@code model_config} 中的生效配置，未配置或构建失败时回落到 yml 的 bootstrap Bean。</p>
 */
@Slf4j
@Component
public class ChatModelProvider {

    private final ModelProfileResolver profileResolver;
    private final ModelClientFactory clientFactory;

    /** yml bootstrap 兜底：Chat（DeepSeek） */
    private final OpenAiChatModel fallbackChatModel;
    /** yml bootstrap 兜底：流式 Chat */
    private final OpenAiStreamingChatModel fallbackStreamingChatModel;

    /** yml bootstrap 兜底：本地改写模型（可选，未启用本地改写时为 null） */
    @Autowired(required = false)
    private OllamaChatModel fallbackRewriteModel;

    public ChatModelProvider(ModelProfileResolver profileResolver,
                             ModelClientFactory clientFactory,
                             OpenAiChatModel fallbackChatModel,
                             OpenAiStreamingChatModel fallbackStreamingChatModel) {
        this.profileResolver = profileResolver;
        this.clientFactory = clientFactory;
        this.fallbackChatModel = fallbackChatModel;
        this.fallbackStreamingChatModel = fallbackStreamingChatModel;
    }

    /** 当前生效的对话模型。 */
    public ChatModel chat() {
        ModelProfile profile = safeResolve(ModelBizType.CHAT);
        if (profile == null) {
            return fallbackChatModel;
        }
        try {
            return clientFactory.chat(profile);
        } catch (Exception e) {
            log.warn("构建对话模型失败，回落 yml 配置: {}", e.getMessage());
            return fallbackChatModel;
        }
    }

    /** 当前生效的流式对话模型。 */
    public StreamingChatModel streaming() {
        ModelProfile profile = safeResolve(ModelBizType.CHAT);
        if (profile == null) {
            return fallbackStreamingChatModel;
        }
        try {
            return clientFactory.streaming(profile);
        } catch (Exception e) {
            log.warn("构建流式对话模型失败，回落 yml 配置: {}", e.getMessage());
            return fallbackStreamingChatModel;
        }
    }

    /** 当前生效的查询改写模型；不可用时返回 null。 */
    public ChatModel rewrite() {
        ModelProfile profile = safeResolve(ModelBizType.REWRITE);
        if (profile == null) {
            return fallbackRewriteModel;
        }
        try {
            return clientFactory.chat(profile);
        } catch (Exception e) {
            log.warn("构建改写模型失败，回落 yml 配置: {}", e.getMessage());
            return fallbackRewriteModel;
        }
    }

    /**
     * 是否配置了生效的查询改写模型。
     *
     * <p>仅判断配置是否存在，不代表模型服务可达；实际调用失败由调用方降级处理。</p>
     */
    public boolean isRewriteConfigured() {
        return safeResolve(ModelBizType.REWRITE) != null;
    }

    private ModelProfile safeResolve(ModelBizType type) {
        try {
            return profileResolver.resolve(type);
        } catch (Exception e) {
            log.warn("解析模型配置失败，回落 yml 配置: type={}, err={}", type, e.getMessage());
            return null;
        }
    }
}
