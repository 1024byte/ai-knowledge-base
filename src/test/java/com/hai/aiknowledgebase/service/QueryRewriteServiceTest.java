package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.*;
import com.hai.aiknowledgebase.queryrewrite.*;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QueryRewriteService 单元测试
 * <p>
 * 覆盖场景：
 * 1. 全局开关（启用/禁用）
 * 2. 空查询处理
 * 3. LLM 改写（本地 LLM 可用）
 * 4. L1 规则降级（本地 LLM 不可用）
 * 5. 排除词提取
 */
@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceTest {

    @Mock
    private QueryRewriteConfigLoader configLoader;

    @Mock
    private QueryCorrector queryCorrector;

    @Mock
    private QueryRouter queryRouter;

    @Mock
    private OpenAiChatModel chatModel;

    @Mock
    private L1RuleBasedTransformer l1RuleBasedTransformer;

    @Mock
    private LocalLLMRewriter localLLMRewriter;

    @InjectMocks
    private QueryRewriteService queryRewriteService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(queryRewriteService, "enabled", true);
        ReflectionTestUtils.setField(queryRewriteService, "l1ConfidenceThreshold", 0.85);
        ReflectionTestUtils.setField(queryRewriteService, "localLLMRewriter", null);

        lenient().when(configLoader.getFixedMapping()).thenReturn(new HashMap<>());
        lenient().when(configLoader.getFixedMappingConfidence()).thenReturn(new HashMap<>());
        lenient().when(configLoader.getSynonymDict()).thenReturn(new HashMap<>());
        lenient().when(configLoader.isStopWord(anyString())).thenReturn(false);

        lenient().when(queryCorrector.correct(anyString())).thenAnswer(inv -> inv.getArgument(0));

        lenient().when(l1RuleBasedTransformer.applyRuleRewrite(anyString())).thenReturn(
                QueryRewriteResult.builder()
                        .rewrittenQuery("")
                        .expandKeywords(Collections.emptyList())
                        .excludeKeywords(Collections.emptyList())
                        .confidence(0.0)
                        .path(RewritePath.L1_RULE)
                        .build()
        );
    }

    // ==================== 场景1：全局开关 ====================

    @Nested
    @DisplayName("场景1：全局开关控制")
    class GlobalSwitch {

        @Test
        @DisplayName("禁用时应直接返回原始查询")
        void disabledReturnsOriginalQuery() {
            ReflectionTestUtils.setField(queryRewriteService, "enabled", false);

            QueryRewriteResult result = queryRewriteService.rewrite("测试查询");

            assertThat(result.getRewrittenQuery()).isEqualTo("测试查询");
            assertThat(result.getPath()).isEqualTo(RewritePath.NONE);
            assertThat(result.getConfidence()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("启用时应执行改写流程")
        void enabledRunsRewrite() {
            QueryRewriteResult mockResult = QueryRewriteResult.builder()
                    .rewrittenQuery("改写后查询")
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.9)
                    .path(RewritePath.L1_RULE)
                    .build();
            when(l1RuleBasedTransformer.applyRuleRewrite(anyString())).thenReturn(mockResult);

            QueryRewriteResult result = queryRewriteService.rewrite("测试查询");

            assertThat(result.getPath()).isEqualTo(RewritePath.L1_RULE);
        }
    }

    // ==================== 场景2：空查询处理 ====================

    @Nested
    @DisplayName("场景2：空查询处理")
    class EmptyQuery {

        @Test
        @DisplayName("null 查询应返回空字符串")
        void nullQueryReturnsEmpty() {
            QueryRewriteResult result = queryRewriteService.rewrite((String) null);

            assertThat(result.getRewrittenQuery()).isEmpty();
            assertThat(result.getPath()).isEqualTo(RewritePath.L1_RULE);
        }

        @Test
        @DisplayName("空白查询应返回空字符串")
        void blankQueryReturnsEmpty() {
            QueryRewriteResult result = queryRewriteService.rewrite("   ");

            assertThat(result.getRewrittenQuery()).isEmpty();
            assertThat(result.getPath()).isEqualTo(RewritePath.L1_RULE);
        }

        @Test
        @DisplayName("空字符串应返回空字符串")
        void emptyQueryReturnsEmpty() {
            QueryRewriteResult result = queryRewriteService.rewrite("");

            assertThat(result.getRewrittenQuery()).isEmpty();
            assertThat(result.getPath()).isEqualTo(RewritePath.L1_RULE);
        }
    }

    // ==================== 场景3：LLM 改写优先 ====================

    @Nested
    @DisplayName("场景3：本地 LLM 改写优先")
    class LLMRewrite {

        @Test
        @DisplayName("本地 LLM 可用时应优先使用 LLM 改写")
        void llmUsedWhenAvailable() {
            ReflectionTestUtils.setField(queryRewriteService, "localLLMRewriter", localLLMRewriter);

            List<String> historyTexts = Arrays.asList("用户：什么是EmbeddingStore");
            QueryRewriteResult llmResult = QueryRewriteResult.builder()
                    .rewrittenQuery("EmbeddingStore 向量存储")
                    .expandKeywords(List.of("向量", "存储"))
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.95)
                    .path(RewritePath.LLM_REWRITE)
                    .build();

            when(localLLMRewriter.isAvailable()).thenReturn(true);
            when(localLLMRewriter.rewrite(anyString(), anyList())).thenReturn(llmResult);

            RewriteRequest request = RewriteRequest.builder()
                    .query("它是什么")
                    .strategy(RewriteStrategyEnum.SIMPLE_REWRITE)
                    .build();

            QueryRewriteResult result = queryRewriteService.rewrite(request);

            assertThat(result.getPath()).isEqualTo(RewritePath.LLM_REWRITE);
            assertThat(result.getRewrittenQuery()).isEqualTo("EmbeddingStore 向量存储");
        }

        @Test
        @DisplayName("本地 LLM 不可用时应降级到 L1 规则")
        void fallbackToL1WhenLLMNotAvailable() {
            QueryRewriteResult l1Result = QueryRewriteResult.builder()
                    .rewrittenQuery("测试查询")
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.85)
                    .path(RewritePath.L1_RULE)
                    .build();
            when(l1RuleBasedTransformer.applyRuleRewrite(anyString())).thenReturn(l1Result);

            QueryRewriteResult result = queryRewriteService.rewrite("测试查询");

            assertThat(result.getPath()).isEqualTo(RewritePath.L1_RULE);
        }
    }

    // ==================== 场景4：L1 规则改写 ====================

    @Nested
    @DisplayName("场景4：L1 规则改写降级")
    class L1Fallback {

        @Test
        @DisplayName("L1 规则应返回正确的改写结果")
        void l1ReturnsCorrectResult() {
            QueryRewriteResult l1Result = QueryRewriteResult.builder()
                    .rewrittenQuery("配置API接口")
                    .expandKeywords(List.of("API", "接口"))
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.9)
                    .path(RewritePath.L1_RULE)
                    .build();
            when(l1RuleBasedTransformer.applyRuleRewrite("配置API")).thenReturn(l1Result);

            QueryRewriteResult result = queryRewriteService.rewrite("配置API");

            assertThat(result.getRewrittenQuery()).isEqualTo("配置API接口");
            assertThat(result.getExpandKeywords()).contains("API", "接口");
        }

        @Test
        @DisplayName("纠错服务应在改写前执行")
        void correctionAppliedBeforeRewrite() {
            when(queryCorrector.correct("配质API")).thenReturn("配置API");

            QueryRewriteResult l1Result = QueryRewriteResult.builder()
                    .rewrittenQuery("配置API接口")
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.9)
                    .path(RewritePath.L1_RULE)
                    .build();
            when(l1RuleBasedTransformer.applyRuleRewrite("配置API")).thenReturn(l1Result);

            QueryRewriteResult result = queryRewriteService.rewrite("配质API");

            assertThat(result.getRewrittenQuery()).isEqualTo("配置API接口");
            verify(queryCorrector).correct("配质API");
        }
    }

    // ==================== 场景5：排除词提取 ====================

    @Nested
    @DisplayName("场景5：排除词提取")
    class ExcludeKeywordExtraction {

        @Test
        @DisplayName("\"不包含\" 模式应正确提取排除词")
        void extractExcludeKeywordWithNotContain() {
            QueryRewriteResult l1Result = QueryRewriteResult.builder()
                    .rewrittenQuery("查询方法")
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(List.of("删除"))
                    .confidence(0.9)
                    .path(RewritePath.L1_RULE)
                    .build();
            when(l1RuleBasedTransformer.applyRuleRewrite("查询方法不包含删除")).thenReturn(l1Result);

            QueryRewriteResult result = queryRewriteService.rewrite("查询方法不包含删除");

            assertThat(result.getExcludeKeywords()).contains("删除");
        }

        @Test
        @DisplayName("\"除了\" 模式应正确提取排除词")
        void extractExcludeKeywordWithExcept() {
            QueryRewriteResult l1Result = QueryRewriteResult.builder()
                    .rewrittenQuery("查询方法")
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(List.of("新增"))
                    .confidence(0.9)
                    .path(RewritePath.L1_RULE)
                    .build();
            when(l1RuleBasedTransformer.applyRuleRewrite("查询方法除了新增")).thenReturn(l1Result);

            QueryRewriteResult result = queryRewriteService.rewrite("查询方法除了新增");

            assertThat(result.getExcludeKeywords()).contains("新增");
        }

        @Test
        @DisplayName("无排除词时排除词列表应为空")
        void noExcludeKeywordsWhenNoPattern() {
            QueryRewriteResult l1Result = QueryRewriteResult.builder()
                    .rewrittenQuery("查询方法列表")
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.9)
                    .path(RewritePath.L1_RULE)
                    .build();
            when(l1RuleBasedTransformer.applyRuleRewrite("查询方法列表")).thenReturn(l1Result);

            QueryRewriteResult result = queryRewriteService.rewrite("查询方法列表");

            assertThat(result.getExcludeKeywords()).isEmpty();
        }
    }

    // ==================== 场景6：策略路由 ====================

    @Nested
    @DisplayName("场景6：策略路由与改写路径")
    class StrategyRouting {

        @Test
        @DisplayName("SIMPLE_REWRITE 策略应走完整改写流程")
        void simpleRewriteStrategy() {
            QueryRewriteResult l1Result = QueryRewriteResult.builder()
                    .rewrittenQuery("改写后查询")
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.9)
                    .path(RewritePath.L1_RULE)
                    .build();
            when(l1RuleBasedTransformer.applyRuleRewrite(anyString())).thenReturn(l1Result);

            RewriteRequest request = RewriteRequest.builder()
                    .query("测试查询")
                    .strategy(RewriteStrategyEnum.SIMPLE_REWRITE)
                    .build();

            QueryRewriteResult result = queryRewriteService.rewrite(request);

            assertThat(result.getPath()).isEqualTo(RewritePath.L1_RULE);
        }

        @Test
        @DisplayName("DIRECT 策略应跳过改写")
        void directStrategySkipsRewrite() {
            QueryRewriteResult l1Result = QueryRewriteResult.builder()
                    .rewrittenQuery("测试查询")
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.0)
                    .path(RewritePath.NONE)
                    .build();
            when(l1RuleBasedTransformer.applyRuleRewrite(anyString())).thenReturn(l1Result);

            RewriteRequest request = RewriteRequest.builder()
                    .query("测试查询")
                    .strategy(RewriteStrategyEnum.DIRECT)
                    .build();

            QueryRewriteResult result = queryRewriteService.rewrite(request);

            assertThat(result.getRewrittenQuery()).isEqualTo("测试查询");
        }
    }
}