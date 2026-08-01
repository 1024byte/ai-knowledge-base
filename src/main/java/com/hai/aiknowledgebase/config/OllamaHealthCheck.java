package com.hai.aiknowledgebase.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Ollama 服务健康检查
 *
 * <p>检查 Ollama 服务是否可用，以及目标模型是否已加载。</p>
 */
@Slf4j
@Component
public class OllamaHealthCheck implements HealthIndicator {

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public Health health() {
        try {
            String url = "http://localhost:11434/api/tags";
            String response = restTemplate.getForObject(url, String.class);
            if (response != null && response.contains("qwen2.5")) {
                return Health.up().withDetail("ollama", "running").withDetail("model", "loaded").build();
            }
            return Health.up().withDetail("ollama", "running").withDetail("model", "not loaded").build();
        } catch (Exception e) {
            return Health.down().withDetail("ollama", "unreachable").withException(e).build();
        }
    }
}