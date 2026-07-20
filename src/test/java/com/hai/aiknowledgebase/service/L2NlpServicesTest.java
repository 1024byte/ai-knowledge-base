package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.QueryIntent;
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
 * - QueryIntentClassifier（意图识别）
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

    // ==================== QueryIntentClassifier 测试 ====================

    @Nested
    @DisplayName("QueryIntentClassifier 意图识别")
    class IntentClassifierTest {

        private QueryIntentClassifier classifier;

        @Mock
        private ChineseTokenizerService tokenizerService;

        @BeforeEach
        void setUp() {
            // 默认返回足够多的关键词，避免被判定为 AMBIGUOUS
            lenient().when(tokenizerService.tokenize(anyString())).thenReturn(List.of("配置", "API", "接口"));
            classifier = new QueryIntentClassifier(tokenizerService);
        }

        @Test
        @DisplayName("包含'如何'应识别为 PROCEDURAL")
        void proceduralIntent() {
            assertThat(classifier.classify("如何配置API接口")).isEqualTo(QueryIntent.PROCEDURAL);
        }

        @Test
        @DisplayName("包含'怎么'应识别为 PROCEDURAL")
        void proceduralIntentWithZenme() {
            assertThat(classifier.classify("怎么配置数据库")).isEqualTo(QueryIntent.PROCEDURAL);
        }

        @Test
        @DisplayName("包含'什么是'应识别为 DEFINITIONAL")
        void definitionalIntent() {
            assertThat(classifier.classify("什么是向量召回")).isEqualTo(QueryIntent.DEFINITIONAL);
        }

        @Test
        @DisplayName("包含'定义'应识别为 DEFINITIONAL")
        void definitionalIntentWithDef() {
            assertThat(classifier.classify("API网关的定义")).isEqualTo(QueryIntent.DEFINITIONAL);
        }

        @Test
        @DisplayName("包含'区别'应识别为 COMPARISON")
        void comparisonIntent() {
            assertThat(classifier.classify("专升本和自考的区别")).isEqualTo(QueryIntent.COMPARISON);
        }

        @Test
        @DisplayName("包含'对比'应识别为 COMPARISON")
        void comparisonIntentWithDuibi() {
            assertThat(classifier.classify("MySQL对比PostgreSQL")).isEqualTo(QueryIntent.COMPARISON);
        }

        @Test
        @DisplayName("包含'多少'应识别为 FACTUAL")
        void factualIntent() {
            assertThat(classifier.classify("专升本需要多少词汇量")).isEqualTo(QueryIntent.FACTUAL);
        }

        @Test
        @DisplayName("包含'是否'应识别为 FACTUAL")
        void factualIntentWithShifou() {
            assertThat(classifier.classify("是否支持中文分词")).isEqualTo(QueryIntent.FACTUAL);
        }

        @Test
        @DisplayName("代词占比高且无其他意图标记应识别为 AMBIGUOUS")
        void ambiguousIntentWithPronouns() {
            // "那个东西" 无其他意图关键词，代词占比高
            assertThat(classifier.classify("那个东西")).isEqualTo(QueryIntent.AMBIGUOUS);
        }

        @Test
        @DisplayName("关键词过少应识别为 AMBIGUOUS")
        void ambiguousIntentWithFewKeywords() {
            when(tokenizerService.tokenize(anyString())).thenReturn(Collections.emptyList());
            assertThat(classifier.classify("查")).isEqualTo(QueryIntent.AMBIGUOUS);
        }

        @Test
        @DisplayName("空查询应识别为 AMBIGUOUS")
        void ambiguousIntentWithEmpty() {
            assertThat(classifier.classify("")).isEqualTo(QueryIntent.AMBIGUOUS);
            assertThat(classifier.classify(null)).isEqualTo(QueryIntent.AMBIGUOUS);
        }

        @Test
        @DisplayName("无特殊意图标记的查询默认为 FACTUAL")
        void defaultFactualIntent() {
            assertThat(classifier.classify("API配置说明")).isEqualTo(QueryIntent.FACTUAL);
        }

        @Test
        @DisplayName("DEFINITIONAL 优先级高于 PROCEDURAL")
        void definitionalOverProcedural() {
            // "什么是" 应优先匹配 DEFINITIONAL
            assertThat(classifier.classify("什么是如何配置")).isEqualTo(QueryIntent.DEFINITIONAL);
        }
    }

    // ==================== QueryCorrector 测试 ====================

    @Nested
    @DisplayName("QueryCorrector 查询纠错")
    class QueryCorrectorTest {

        private QueryCorrector corrector;

        @Mock
        private ChineseTokenizerService tokenizerService;

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

            corrector = new QueryCorrector(configLoader, tokenizerService);
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
            assertThat(corrector.hasCorrection()).isTrue();
        }

        @Test
        @DisplayName("hasCorrection 对正确查询应返回 false")
        void noCorrectionForCorrect() {
            corrector.correct("配置API");
            assertThat(corrector.hasCorrection()).isFalse();
        }
    }
}