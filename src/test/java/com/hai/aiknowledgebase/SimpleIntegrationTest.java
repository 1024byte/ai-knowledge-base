package com.hai.aiknowledgebase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SimpleIntegrationTest {

    @Test
    void contextLoads() {
        // 简单测试Spring上下文加载
        assertThat(true).isTrue();
        System.out.println("Spring上下文加载测试通过");
    }

    @Test
    void testApplicationStartup() {
        // 测试应用启动
        System.out.println("应用启动测试通过");
        assertThat(true).isTrue();
    }
}