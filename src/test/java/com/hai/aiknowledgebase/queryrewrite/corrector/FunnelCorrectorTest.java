package com.hai.aiknowledgebase.queryrewrite.corrector;

import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import com.hai.aiknowledgebase.service.QueryRewriteConfigLoader;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 三层漏斗纠错器单元测试
 *
 * <h2>覆盖范围</h2>
 * <ul>
 *   <li>WordCheckerCorrector（L1）：word-checker 内置词典拼写检查</li>
 *   <li>PinyinCorrector（L2）：字符编辑距离 + 拼音匹配</li>
 *   <li>LLMCorrector（L3）：LLM 语义兜底</li>
 *   <li>FunnelQueryCorrector：三层漏斗编排逻辑</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FunnelCorrectorTest {

    @Mock
    private QueryRewriteConfigLoader configLoader;

    @Mock
    private ChineseTokenizerService tokenizerService;

    @Mock
    private OpenAiChatModel chatModel;

    // ==================== L1: WordCheckerCorrector 测试 ====================

    @Nested
    @DisplayName("L1: WordCheckerCorrector")
    class WordCheckerCorrectorTest {

        private WordCheckerCorrector corrector;

        @BeforeEach
        void setUp() {
            corrector = new WordCheckerCorrector(tokenizerService);
        }

        @Test
        @DisplayName("查询为空应返回原始值")
        void shouldReturnOriginalForNullAndBlank() {
            CorrectionResult r1 = corrector.correct(null);
            assertThat(r1.getCorrectedQuery()).isEmpty();
            assertThat(r1.isCorrected()).isFalse();
            assertThat(r1.getConfidence()).isEqualTo(1.0);

            CorrectionResult r2 = corrector.correct("   ");
            assertThat(r2.getCorrectedQuery()).isEqualTo("   ");
            assertThat(r2.isCorrected()).isFalse();
        }

        @Test
        @DisplayName("分词为空应返回原始查询")
        void shouldReturnOriginalWhenTokensEmpty() {
            when(tokenizerService.tokenize(anyString(), eq(false)))
                    .thenReturn(Collections.emptyList());

            CorrectionResult result = corrector.correct("向量化存储");

            assertThat(result.getCorrectedQuery()).isEqualTo("向量化存储");
            assertThat(result.isCorrected()).isFalse();
        }

        @Test
        @DisplayName("word-checker 判定所有 token 正确 → 置信度 1.0")
        void shouldReturnConfidenceOneWhenAllCorrect() {
            when(tokenizerService.tokenize("hello world", false))
                    .thenReturn(List.of("hello", "world"));

            CorrectionResult result = corrector.correct("hello world");

            // "hello" 和 "world" 在 word-checker 内置词典中，isCorrect=true
            assertThat(result.isCorrected()).isFalse();
            assertThat(result.getConfidence()).isEqualTo(1.0);
            assertThat(result.getLayer()).isEqualTo("L1");
        }

        @Test
        @DisplayName("应返回纠错明细")
        void shouldReturnCorrectionDetails() {
            when(tokenizerService.tokenize("向量化存诸", false))
                    .thenReturn(List.of("向量化", "存诸"));

            CorrectionResult result = corrector.correct("向量化存诸");

            assertThat(result.getLayer()).isEqualTo("L1");
            // 即使未纠正，也要有 details
            assertThat(result.getDetails()).isNotNull();
            assertThat(result.getCostMs()).isGreaterThanOrEqualTo(0);
            assertThat(result.getOriginalQuery()).isEqualTo("向量化存诸");
        }

        @Test
        @DisplayName("无纠错时 details 应为空列表")
        void shouldHaveEmptyDetailsWhenNoCorrection() {
            when(tokenizerService.tokenize("hello world", false))
                    .thenReturn(List.of("hello", "world"));

            CorrectionResult result = corrector.correct("hello world");

            assertThat(result.getDetails()).isEmpty();
            assertThat(result.isCorrected()).isFalse();
        }
    }

    // ==================== L2: PinyinCorrector 测试 ====================

    @Nested
    @DisplayName("L2: PinyinCorrector")
    class PinyinCorrectorTest {

        private PinyinCorrector corrector;

        @BeforeEach
        void setUp() {
            corrector = new PinyinCorrector(tokenizerService, configLoader);
        }

        @Test
        @DisplayName("查询为空应返回原始值")
        void shouldReturnOriginalForNullAndBlank() {
            CorrectionResult r1 = corrector.correct(null);
            assertThat(r1.getCorrectedQuery()).isEmpty();
            assertThat(r1.isCorrected()).isFalse();

            CorrectionResult r2 = corrector.correct("\n");
            assertThat(r2.getCorrectedQuery()).isEqualTo("\n");
            assertThat(r2.isCorrected()).isFalse();
        }

        @Test
        @DisplayName("所有 token 在词典中应返回原始查询")
        void shouldReturnOriginalWhenAllTokensInDict() {
            when(configLoader.getAllDictionaryKeys())
                    .thenReturn(Set.of("向量化", "存储"));
            when(tokenizerService.tokenize("向量化存储", false))
                    .thenReturn(List.of("向量化", "存储"));

            CorrectionResult result = corrector.correct("向量化存储");

            assertThat(result.getCorrectedQuery()).isEqualTo("向量化存储");
            assertThat(result.isCorrected()).isFalse();
            assertThat(result.getConfidence()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("字符编辑距离匹配：存诸→存储（编辑距离1）")
        void shouldCorrectByCharEditDistance() {
            when(configLoader.getAllDictionaryKeys())
                    .thenReturn(Set.of("向量化", "存储", "检索"));
            when(tokenizerService.tokenize("向量化存诸", false))
                    .thenReturn(List.of("向量化", "存诸"));

            CorrectionResult result = corrector.correct("向量化存诸");

            assertThat(result.getCorrectedQuery()).isEqualTo("向量化存储");
            assertThat(result.isCorrected()).isTrue();
            assertThat(result.getLayer()).isEqualTo("L2");
            assertThat(result.getDetails()).hasSize(1);
            assertThat(result.getDetails().get(0).getOriginal()).isEqualTo("存诸");
            assertThat(result.getDetails().get(0).getCorrected()).isEqualTo("存储");
            assertThat(result.getDetails().get(0).getReason()).isEqualTo("编辑距离");
        }

        @Test
        @DisplayName("拼音匹配：同音字纠错（吉和→集合，编辑距离2）")
        void shouldCorrectByPinyin() {
            // "吉和"和"集合"字符编辑距离=2，但拼音完全相同(jí hé)
            // 字符编辑距离路径失败，拼音路径命中
            when(configLoader.getAllDictionaryKeys())
                    .thenReturn(Set.of("集合", "检索"));
            when(tokenizerService.tokenize("吉和检索", false))
                    .thenReturn(List.of("吉和", "检索"));

            CorrectionResult result = corrector.correct("吉和检索");

            assertThat(result.getCorrectedQuery()).isEqualTo("集合检索");
            assertThat(result.isCorrected()).isTrue();
            assertThat(result.getDetails().get(0).getReason()).isEqualTo("同音字");
        }

        @Test
        @DisplayName("完全不同词不应被纠正")
        void shouldNotCorrectUnrelatedWords() {
            when(configLoader.getAllDictionaryKeys())
                    .thenReturn(Set.of("存储", "检索"));
            when(tokenizerService.tokenize("动词检索", false))
                    .thenReturn(List.of("动词", "检索"));

            CorrectionResult result = corrector.correct("动词检索");

            assertThat(result.getCorrectedQuery()).isEqualTo("动词检索");
            assertThat(result.isCorrected()).isFalse();
        }

        @Test
        @DisplayName("纯英文 token 不应触发拼音匹配")
        void shouldSkipPinyinForNonChinese() {
            when(configLoader.getAllDictionaryKeys())
                    .thenReturn(Set.of("API", "Redis"));
            when(tokenizerService.tokenize("API Redis", false))
                    .thenReturn(List.of("API", "Redis"));

            CorrectionResult result = corrector.correct("API Redis");

            assertThat(result.getCorrectedQuery()).isEqualTo("API Redis");
            assertThat(result.isCorrected()).isFalse();
        }

        @Test
        @DisplayName("空词典应返回原始查询")
        void shouldReturnOriginalWhenDictEmpty() {
            when(configLoader.getAllDictionaryKeys()).thenReturn(Collections.emptySet());
            when(tokenizerService.tokenize("向量化存储", false))
                    .thenReturn(List.of("向量化", "存储"));

            CorrectionResult result = corrector.correct("向量化存储");

            assertThat(result.getCorrectedQuery()).isEqualTo("向量化存储");
            assertThat(result.isCorrected()).isFalse();
        }
    }

    // ==================== L3: LLMCorrector 测试 ====================

    @Nested
    @DisplayName("L3: LLMCorrector")
    class LLMCorrectorTest {

        private LLMCorrector corrector;

        @BeforeEach
        void setUp() {
            corrector = new LLMCorrector(chatModel);
        }

        @Test
        @DisplayName("查询为空应返回原始值")
        void shouldReturnOriginalForNullAndBlank() {
            CorrectionResult r1 = corrector.correct(null);
            assertThat(r1.getCorrectedQuery()).isEmpty();
            assertThat(r1.isCorrected()).isFalse();

            CorrectionResult r2 = corrector.correct("  ");
            assertThat(r2.getCorrectedQuery()).isEqualTo("  ");
            assertThat(r2.isCorrected()).isFalse();
        }

        @Test
        @DisplayName("LLM 成功纠错应返回纠正后文本")
        void shouldReturnCorrectedTextWhenLLMSucceeds() {
            ChatResponse mockResponse = mock(ChatResponse.class);
            when(mockResponse.aiMessage()).thenReturn(AiMessage.from("向量化存储是什么"));
            when(chatModel.chat(any(), any())).thenReturn(mockResponse);

            CorrectionResult result = corrector.correct("向量化存诸是什么");

            assertThat(result.getCorrectedQuery()).isEqualTo("向量化存储是什么");
            assertThat(result.isCorrected()).isTrue();
            assertThat(result.getLayer()).isEqualTo("L3");
            assertThat(result.getConfidence()).isCloseTo(0.60, within(0.01));
            assertThat(result.getDetails()).hasSize(1);
            assertThat(result.getDetails().get(0).getReason()).isEqualTo("LLM语义");
        }

        @Test
        @DisplayName("LLM 返回原始查询应标记为无纠错")
        void shouldMarkNoCorrectionWhenLLMReturnsOriginal() {
            ChatResponse mockResponse = mock(ChatResponse.class);
            when(mockResponse.aiMessage()).thenReturn(AiMessage.from("向量化存储是什么"));
            when(chatModel.chat(any(), any())).thenReturn(mockResponse);

            CorrectionResult result = corrector.correct("向量化存储是什么");

            assertThat(result.getCorrectedQuery()).isEqualTo("向量化存储是什么");
            assertThat(result.isCorrected()).isFalse();
        }

        @Test
        @DisplayName("LLM 异常应返回原始查询")
        void shouldReturnOriginalWhenLLMThrowsException() {
            when(chatModel.chat(any(), any()))
                    .thenThrow(new RuntimeException("LLM timeout"));

            CorrectionResult result = corrector.correct("向量化存诸是什么");

            assertThat(result.getCorrectedQuery()).isEqualTo("向量化存诸是什么");
            assertThat(result.isCorrected()).isFalse();
            assertThat(result.getLayer()).isEqualTo("L3");
        }

        @Test
        @DisplayName("LLM 返回带前后空格的修正应正确 trim")
        void shouldTrimLLMResponse() {
            ChatResponse mockResponse = mock(ChatResponse.class);
            when(mockResponse.aiMessage()).thenReturn(AiMessage.from("  向量化存储  "));
            when(chatModel.chat(any(), any())).thenReturn(mockResponse);

            CorrectionResult result = corrector.correct("向量化存诸");

            assertThat(result.getCorrectedQuery()).isEqualTo("向量化存储");
            assertThat(result.isCorrected()).isTrue();
        }
    }

    // ==================== FunnelQueryCorrector 编排测试 ====================

    @Nested
    @DisplayName("FunnelQueryCorrector 三层漏斗编排")
    class FunnelQueryCorrectorTest {

        private FunnelQueryCorrector funnel;

        @Mock
        private WordCheckerCorrector l1;

        @Mock
        private PinyinCorrector l2;

        @Mock
        private LLMCorrector l3;

        @BeforeEach
        void setUp() {
            funnel = new FunnelQueryCorrector(l1, l2, l3);
            ReflectionTestUtils.setField(funnel, "l1Threshold", 0.85);
            ReflectionTestUtils.setField(funnel, "l1TimeoutMs", 50L);
            ReflectionTestUtils.setField(funnel, "l2Threshold", 0.80);
            ReflectionTestUtils.setField(funnel, "l2TimeoutMs", 200L);
            ReflectionTestUtils.setField(funnel, "l3TimeoutMs", 2000L);

            lenient().when(l1.getLayerName()).thenReturn("L1");
            lenient().when(l2.getLayerName()).thenReturn("L2");
            lenient().when(l3.getLayerName()).thenReturn("L3");
        }

        @Test
        @DisplayName("L1 高置信度命中 → 直接返回，不调用 L2/L3")
        void shouldReturnL1WhenHighConfidence() {
            when(l1.correct("向量化存诸"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("向量化存诸")
                            .correctedQuery("向量化存储")
                            .confidence(0.90)
                            .layer("L1")
                            .corrected(true)
                            .build());

            String result = funnel.correct("向量化存诸");

            assertThat(result).isEqualTo("向量化存储");
        }

        @Test
        @DisplayName("L1 低置信度 → 降级到 L2，L2 命中 → 返回")
        void shouldFallbackToL2WhenL1LowConfidence() {
            when(l1.correct("向量化存诸"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("向量化存诸")
                            .correctedQuery("向量化存诸")
                            .confidence(0.70)
                            .layer("L1")
                            .corrected(false)
                            .build());
            when(l2.correct("向量化存诸"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("向量化存诸")
                            .correctedQuery("向量化存储")
                            .confidence(0.85)
                            .layer("L2")
                            .corrected(true)
                            .build());

            String result = funnel.correct("向量化存诸");

            assertThat(result).isEqualTo("向量化存储");
        }

        @Test
        @DisplayName("L1/L2 都失败 → 降级到 L3，L3 兜底返回")
        void shouldFallbackToL3WhenL1L2Fail() {
            when(l1.correct("持久花配置"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("持久花配置")
                            .correctedQuery("持久花配置")
                            .confidence(0.70)
                            .layer("L1")
                            .corrected(false)
                            .build());
            when(l2.correct("持久花配置"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("持久花配置")
                            .correctedQuery("持久花配置")
                            .confidence(0.60)
                            .layer("L2")
                            .corrected(false)
                            .build());
            when(l3.correct("持久花配置"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("持久花配置")
                            .correctedQuery("持久化配置")
                            .confidence(0.60)
                            .layer("L3")
                            .corrected(true)
                            .build());

            String result = funnel.correct("持久花配置");

            assertThat(result).isEqualTo("持久化配置");
        }

        @Test
        @DisplayName("L1 置信度恰好 0.85 应命中")
        void shouldAcceptL1AtExactThreshold() {
            when(l1.correct("向量化存诸"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("向量化存诸")
                            .correctedQuery("向量化存储")
                            .confidence(0.85)
                            .layer("L1")
                            .corrected(true)
                            .build());

            String result = funnel.correct("向量化存诸");

            assertThat(result).isEqualTo("向量化存储");
        }

        @Test
        @DisplayName("L1 置信度 0.84（低于阈值）应降级")
        void shouldNotAcceptL1BelowThreshold() {
            when(l1.correct("向量化存诸"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("向量化存诸")
                            .correctedQuery("向量化存储")
                            .confidence(0.84)
                            .layer("L1")
                            .corrected(true)
                            .build());
            when(l2.correct("向量化存诸"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("向量化存诸")
                            .correctedQuery("向量化存诸")
                            .confidence(0.70)
                            .layer("L2")
                            .corrected(false)
                            .build());
            when(l3.correct("向量化存诸"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("向量化存诸")
                            .correctedQuery("向量化存储")
                            .confidence(0.60)
                            .layer("L3")
                            .corrected(true)
                            .build());

            String result = funnel.correct("向量化存诸");

            assertThat(result).isEqualTo("向量化存储");
        }

        @Test
        @DisplayName("L2 置信度恰好 0.80 应命中")
        void shouldAcceptL2AtExactThreshold() {
            when(l1.correct("test"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("test")
                            .correctedQuery("test")
                            .confidence(0.70)
                            .layer("L1")
                            .corrected(false)
                            .build());
            when(l2.correct("test"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("test")
                            .correctedQuery("test")
                            .confidence(0.80)
                            .layer("L2")
                            .corrected(false)
                            .build());

            String result = funnel.correct("test");

            assertThat(result).isEqualTo("test");
        }

        @Test
        @DisplayName("所有层都返回原始查询 → 返回原始查询")
        void shouldReturnOriginalWhenAllLayersNoCorrection() {
            when(l1.correct("hello world"))
                    .thenReturn(CorrectionResult.builder()
                            .originalQuery("hello world")
                            .correctedQuery("hello world")
                            .confidence(1.0)
                            .layer("L1")
                            .corrected(false)
                            .build());

            String result = funnel.correct("hello world");

            assertThat(result).isEqualTo("hello world");
        }

        @Test
        @DisplayName("null 查询应返回空字符串")
        void shouldReturnEmptyForNull() {
            String result = funnel.correct(null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("空白字符串应原样返回")
        void shouldReturnBlankAsIs() {
            String result = funnel.correct("   ");
            assertThat(result).isEqualTo("   ");
        }
    }
}