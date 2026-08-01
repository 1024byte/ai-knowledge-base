package com.hai.aiknowledgebase.config;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ollama.OllamaChatModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static com.hai.aiknowledgebase.queryrewrite.LocalLLMRewriter.REWRITE_SYSTEM_PROMPT;

/**
 * 本地轻量 LLM 配置（Ollama + Qwen2.5 小模型）
 *
 * <p>用于查询改写，替代 L1 规则引擎 + L2 NLP 改写。</p>
 *
 * <p>启用条件：{@code query-rewrite.local-llm.enabled=true}</p>
 */
@Slf4j
@Configuration
public class LocalLlmConfig {

    @Value("${query-rewrite.local-llm.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${query-rewrite.local-llm.model-name:qwen2.5:3b}")
    private String modelName;

    @Value("${query-rewrite.local-llm.temperature:0.1}")
    private Double temperature;

    @Value("${query-rewrite.local-llm.max-tokens:128}")
    private Integer maxTokens;

    @Value("${query-rewrite.local-llm.timeout-ms:1000}")
    private Long timeoutMs;


    @Bean
    @ConditionalOnProperty(name = "query-rewrite.local-llm.enabled", havingValue = "true")
    public OllamaChatModel localRewriteModel() {
        log.info("初始化本地改写 LLM: model={}, baseUrl={}, temperature={}, maxTokens={}, timeout={}ms",
                modelName, baseUrl, temperature, maxTokens, timeoutMs);

        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .numPredict(maxTokens)
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
    }
}