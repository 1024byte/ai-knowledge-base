package com.hai.aiknowledgebase.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
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
    public OpenAiStreamingChatModel openAiStreamingChatModel() {
        log.info("初始化流式Chat模型: {}, baseUrl: {}", chatModel, baseUrl);

        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(chatModel)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}