package com.hai.aiknowledgebase.config;

import com.hai.aiknowledgebase.queryrewrite.LocalLLMRewriter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 本地 LLM 模型预热器
 *
 * <p>使用独立的长超时实例（30s）执行首次推理，将模型加载到 GPU 内存。
 * 与业务 {@link OllamaChatModel} 分离，避免冷启动触发业务超时。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "query-rewrite.local-llm.enabled", havingValue = "true")
public class LocalLlmWarmup implements ApplicationRunner {

    @Value("${query-rewrite.local-llm.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${query-rewrite.local-llm.model-name:qwen2.5:3b}")
    private String modelName;

    @Override
    public void run(ApplicationArguments args) {
        log.info("预热本地 LLM 模型（冷启动，最长等待 30s）...");

        // 独立的长超时实例，仅用于预热
        OllamaChatModel warmupModel = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.0)
                .numPredict(8)
                .timeout(Duration.ofSeconds(30))
                .build();

        try {
            warmupModel.chat(
                    SystemMessage.from(LocalLLMRewriter.REWRITE_SYSTEM_PROMPT),
                    UserMessage.from("预热")
            );
            log.info("本地 LLM 模型预热完成");
        } catch (Exception e) {
            log.warn("本地 LLM 模型预热失败（不影响正常使用）: {}", e.getMessage());
        }
    }
}