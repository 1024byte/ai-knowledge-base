package com.hai.aiknowledgebase.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class ChatModelConfig {

    @Value("${deepseek.api-key}")
    private String apiKey;
    
    @Value("${deepseek.base-url}")
    private String baseUrl;
    
    @Value("${deepseek.chat-model}")
    private String chatModel;
    
    @Value("${deepseek.temperature}")
    private Double temperature;

    @Bean
    public OpenAiChatModel openAiChatModel() {
        log.info("初始化Chat模型: {}, baseUrl: {}", chatModel, baseUrl);
        
        return OpenAiChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(chatModel)
            .temperature(temperature)
            .timeout(Duration.ofSeconds(60))
            .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        // BGE 中文模型，默认使用 CPU，适合生产环境
        return new BgeSmallZhEmbeddingModel();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryStore chatMemoryStore) {
        return MessageWindowChatMemory.builder()
                .id("default")                  // 设置一个默认的会话ID
                .maxMessages(20)                // 限制上下文长度，防止 token 超限
                .chatMemoryStore(chatMemoryStore) // 注入你的持久化存储实现
                .build();
    }
}