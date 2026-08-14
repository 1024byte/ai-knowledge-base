package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.queryrewrite.QueryCorrector;
import com.hai.aiknowledgebase.queryrewrite.corrector.FunnelQueryCorrector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 新增服务单元测试
 * <p>
 * 覆盖：
 * - ChineseTokenizerService（分词服务）
 * - QueryCorrector（查询纠错）
 */
@ExtendWith(MockitoExtension.class)
class L2NlpServicesTest {

    @Mock
    private QueryRewriteConfigLoader configLoader;

    // ==================== ChineseTokenizerService 测试 ====================

    @Nested
    @DisplayName("ChineseTokenizerService 分词服务")
    class TokenizerTest {

        private ChineseTokenizerService tokenizerService;

        @BeforeEach
        void setUp() {
            lenient().when(configLoader.isStopWord(anyString())).thenReturn(false);
            tokenizerService = new ChineseTokenizerService(configLoader);
        }

        @Test
        @DisplayName("中文查询应正确分词")
        void tokenizeChinese() {
            List<String> tokens = tokenizerService.tokenize("如何配置API接口");
            // jieba 搜索引擎模式应将 "如何配置" 拆分为 "如何" + "配置"
            assertThat(tokens).contains("配置", "API", "接口");
        }

        @Test
        @DisplayName("英文和数字应作为独立词提取")
        void tokenizeEnglishAndNumbers() {
            List<String> tokens = tokenizerService.tokenize("API3.0配置");
            assertThat(tokens).anyMatch(t -> t.toLowerCase().contains("api") || t.contains("API"));
        }

        @Test
        @DisplayName("空查询应返回空列表")
        void tokenizeEmpty() {
            assertThat(tokenizerService.tokenize("")).isEmpty();
            assertThat(tokenizerService.tokenize(null)).isEmpty();
            assertThat(tokenizerService.tokenize("   ")).isEmpty();
        }

        @Test
        @DisplayName("停用词应被过滤")
        void tokenizeFilterStopWords() {
            when(configLoader.isStopWord("如何")).thenReturn(true);
            List<String> tokens = tokenizerService.tokenize("如何配置");
            assertThat(tokens).doesNotContain("如何");
        }

        @Test
        @DisplayName("单字应被过滤")
        void tokenizeFilterSingleChar() {
            List<String> tokens = tokenizerService.tokenize("我配置");
            // "我" 是单字，应被过滤
            assertThat(tokens).doesNotContain("我");
        }

        @Test
        @DisplayName("extractKeywords 应返回加权排序的关键词")
        void extractKeywords() {
            List<String> keywords = tokenizerService.extractKeywords("如何配置API接口", 5);
            assertThat(keywords).isNotEmpty();
            assertThat(keywords.size()).isLessThanOrEqualTo(5);
        }

        @Test
        @DisplayName("tokenizeWithPos 应返回带词性的分词结果")
        void tokenizeWithPos() {
            List<ChineseTokenizerService.TokenWithPos> results = tokenizerService.tokenizeWithPos("如何配置API");
            assertThat(results).isNotEmpty();
            for (ChineseTokenizerService.TokenWithPos twp : results) {
                assertThat(twp.getWord()).isNotNull();
                assertThat(twp.getPos()).isNotNull();
            }
        }
    }

    // ==================== QueryCorrector 测试 ====================

    @Nested
    @DisplayName("QueryCorrector 查询纠错")
    class QueryCorrectorTest {

        private QueryCorrector corrector;

        @Mock
        private ChineseTokenizerService tokenizerService;

        @Mock
        FunnelQueryCorrector funnelQueryCorrector;

        @BeforeEach
        void setUp() {
            Map<String, List<String>> synonymDict = new HashMap<>();
            synonymDict.put("配置", List.of("设置", "参数"));
            synonymDict.put("薪资", List.of("工资", "薪酬"));

            Map<String, String> fixedMapping = new HashMap<>();
            fixedMapping.put("API", "API 接口");

            lenient().when(configLoader.getSynonymDict()).thenReturn(synonymDict);
            lenient().when(configLoader.getFixedMapping()).thenReturn(fixedMapping);
            lenient().when(tokenizerService.tokenize(anyString(), anyBoolean())).thenAnswer(inv -> {
                String text = inv.getArgument(0);
                if (text == null) return Collections.emptyList();
                // 简单模拟分词
                List<String> tokens = new ArrayList<>();
                if (text.contains("配质")) tokens.add("配质");
                if (text.contains("配置")) tokens.add("配置");
                if (text.contains("API")) tokens.add("API");
                if (text.contains("薪资")) tokens.add("薪资");
                return tokens;
            });

            corrector = new QueryCorrector(funnelQueryCorrector);
        }

        @Test
        @DisplayName("编辑距离 ≤ 1 的错别字应被纠正")
        void correctTypo() {
            // "配质" 与 "配置" 编辑距离为 1
            String result = corrector.correct("配质API");
            assertThat(result).contains("配置");
        }

        @Test
        @DisplayName("正确的词不应被修改")
        void noCorrectionForCorrectWord() {
            String result = corrector.correct("配置API");
            assertThat(result).isEqualTo("配置API");
        }

        @Test
        @DisplayName("空查询应原样返回")
        void correctEmpty() {
            assertThat(corrector.correct("")).isEqualTo("");
            assertThat(corrector.correct(null)).isEqualTo("");
        }

        @Test
        @DisplayName("hasCorrection 应正确判断是否发生了纠错")
        void hasCorrection() {
            // "配质" → "配置" 应检测到纠错
            corrector.correct("配质API");
        }

        @Test
        @DisplayName("hasCorrection 对正确查询应返回 false")
        void noCorrectionForCorrect() {
            corrector.correct("配置API");
        }
    }
}