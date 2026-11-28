package com.hai.aiknowledgebase.model;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 模型客户端工厂。
 *
 * <p>按配置指纹缓存客户端：配置不变则复用，任一连接相关字段变化则构建新客户端。
 * 缓存有容量上限，旧客户端自然淘汰（HTTP 客户端由 GC 回收，无需显式关闭）。</p>
 */
@Slf4j
@Component
public class ModelClientFactory {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final Cache<String, ChatModel> chatCache = Caffeine.newBuilder()
            .maximumSize(16)
            .build();

    private final Cache<String, StreamingChatModel> streamingCache = Caffeine.newBuilder()
            .maximumSize(16)
            .build();

    /** 构建（或复用缓存的）同步对话客户端。 */
    public ChatModel chat(ModelProfile profile) {
        return chatCache.get(profile.fingerprint(), key -> buildChat(profile));
    }

    /** 构建（或复用缓存的）流式对话客户端。 */
    public StreamingChatModel streaming(ModelProfile profile) {
        return streamingCache.get(profile.fingerprint(), key -> buildStreaming(profile));
    }

    /** 直接构建一个不走缓存的同步客户端（连通性测试使用）。 */
    public ChatModel buildChat(ModelProfile profile) {
        ModelProviderType provider = profile.getProvider();
        if (provider == null) {
            throw new IllegalStateException("模型供应商未配置");
        }
        return switch (provider) {
            case OPENAI_COMPAT -> buildOpenAiChat(profile);
            case OLLAMA -> buildOllamaChat(profile);
            default -> throw new IllegalStateException("该供应商不支持对话模型: " + provider);
        };
    }

    private ChatModel buildOpenAiChat(ModelProfile profile) {
        if (!profile.hasApiKey()) {
            throw new IllegalStateException("OPENAI_COMPAT 模型未配置可用 API Key");
        }
        log.info("构建 OpenAI 兼容对话客户端: model={}, baseUrl={}", profile.getModelName(), profile.getBaseUrl());
        return OpenAiChatModel.builder()
                .apiKey(profile.getApiKey())
                .baseUrl(profile.getBaseUrl())
                .modelName(profile.getModelName())
                .temperature(profile.getTemperature())
                .maxTokens(profile.getMaxTokens())
                .timeout(timeoutOf(profile))
                .build();
    }

    private ChatModel buildOllamaChat(ModelProfile profile) {
        log.info("构建 Ollama 对话客户端: model={}, baseUrl={}", profile.getModelName(), profile.getBaseUrl());
        return OllamaChatModel.builder()
                .baseUrl(profile.getBaseUrl())
                .modelName(profile.getModelName())
                .temperature(profile.getTemperature())
                .numPredict(profile.getMaxTokens())
                .timeout(timeoutOf(profile))
                .build();
    }

    private StreamingChatModel buildStreaming(ModelProfile profile) {
        ModelProviderType provider = profile.getProvider();
        if (provider == null) {
            throw new IllegalStateException("模型供应商未配置");
        }
        return switch (provider) {
            case OPENAI_COMPAT -> buildOpenAiStreaming(profile);
            case OLLAMA -> buildOllamaStreaming(profile);
            default -> throw new IllegalStateException("该供应商不支持流式对话: " + provider);
        };
    }

    private StreamingChatModel buildOpenAiStreaming(ModelProfile profile) {
        if (!profile.hasApiKey()) {
            throw new IllegalStateException("OPENAI_COMPAT 模型未配置可用 API Key");
        }
        return OpenAiStreamingChatModel.builder()
                .apiKey(profile.getApiKey())
                .baseUrl(profile.getBaseUrl())
                .modelName(profile.getModelName())
                .temperature(profile.getTemperature())
                .maxTokens(profile.getMaxTokens())
                .timeout(timeoutOf(profile))
                .build();
    }

    private StreamingChatModel buildOllamaStreaming(ModelProfile profile) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(profile.getBaseUrl())
                .modelName(profile.getModelName())
                .temperature(profile.getTemperature())
                .numPredict(profile.getMaxTokens())
                .timeout(timeoutOf(profile))
                .build();
    }

    private Duration timeoutOf(ModelProfile profile) {
        return profile.getTimeoutMs() != null && profile.getTimeoutMs() > 0
                ? Duration.ofMillis(profile.getTimeoutMs())
                : DEFAULT_TIMEOUT;
    }
}
