package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.*;
import com.hai.aiknowledgebase.interfaces.LocalQueryRewriter;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * QueryRewriteService 单元测试
 * <p>
 * 覆盖场景：
 * 1. 固定映射与同义词对同一词冲突
 * 2. 长词包含短词（如"API网关"与"API"）
 * 3. 多个不重叠的同义词
 * 4. 排除词提取
 * 5. L2 继承 L1 结果
 */
@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceTest {

    @Mock
    private QueryRewriteConfigLoader configLoader;

    @Mock
    private LocalQueryRewriter llmRewriter;

    @Mock
    private ChineseTokenizerService tokenizerService;

    @Mock
    private QueryIntentClassifier intentClassifier;

    @Mock
    private QueryCorrector queryCorrector;

    @InjectMocks
    private QueryRewriteService queryRewriteService;

    @BeforeEach
    void setUp() {
        // 默认配置：启用改写，L1 阈值 0.85，L2 阈值 0.70
        ReflectionTestUtils.setField(queryRewriteService, "enabled", true);
        ReflectionTestUtils.setField(queryRewriteService, "l1ConfidenceThreshold", 0.85);
        ReflectionTestUtils.setField(queryRewriteService, "l2ConfidenceThreshold", 0.70);
        ReflectionTestUtils.setField(queryRewriteService, "llmRewriter", null);

        // 默认空配置
        lenient().when(configLoader.getFixedMapping()).thenReturn(new HashMap<>());
        lenient().when(configLoader.getFixedMappingConfidence()).thenReturn(new HashMap<>());
        lenient().when(configLoader.getSynonymDict()).thenReturn(new HashMap<>());
        lenient().when(configLoader.isStopWord(anyString())).thenReturn(false);

        // 默认 L2 NLP 服务 mock
        lenient().when(queryCorrector.correct(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tokenizerService.tokenize(anyString(), anyBoolean())).thenReturn(Collections.emptyList());
        lenient().when(tokenizerService.extractKeywords(anyString(), anyInt())).thenReturn(Collections.emptyList());
        lenient().when(intentClassifier.classify(anyString())).thenReturn(QueryIntent.FACTUAL);
    }

    // ==================== 场景1：固定映射与同义词对同一词冲突 ====================

    @Nested
    @DisplayName("场景1：固定映射与同义词对同一词冲突")
    class FixedMappingVsSynonymConflict {

        @Test
        @DisplayName("同一词同时出现在固定映射和同义词中时，固定映射优先（长度相同，固定映射排序靠前）")
        void fixedMappingTakesPriorityOverSynonym() {
            // "讲稿" 同时出现在固定映射和同义词中
            Map<String, String> fixedMapping = new HashMap<>();
            fixedMapping.put("讲稿", "讲义");

            Map<String, Double> confidenceMap = new HashMap<>();
            confidenceMap.put("讲稿", 0.95);

            Map<String, List<String>> synonymDict = new HashMap<>();
            synonymDict.put("讲稿", List.of("底稿", "草稿"));

            when(configLoader.getFixedMapping()).thenReturn(fixedMapping);
            when(configLoader.getFixedMappingConfidence()).thenReturn(confidenceMap);
            when(configLoader.getSynonymDict()).thenReturn(synonymDict);

            QueryRewriteResult result = queryRewriteService.rewrite("请帮我找讲稿");

            // 固定映射优先：讲稿 -> 讲义（而非同义词展开 "讲稿 底稿"）
            assertThat(result.getRewrittenQuery()).contains("讲义");
            assertThat(result.getRewrittenQuery()).doesNotContain("底稿");
            assertThat(result.getConfidence()).isEqualTo(0.95);
            assertThat(result.getPath()).isEqualTo(RewritePath.L1_RULE);
        }

        @Test
        @DisplayName("固定映射命中后，同义词的扩展关键词仍应包含同义词列表")
        void fixedMappingHitStillExpandsSynonymKeywords() {
            Map<String, String> fixedMapping = new HashMap<>();
            fixedMapping.put("讲稿", "讲义");

            Map<String, Double> confidenceMap = new HashMap<>();
            confidenceMap.put("讲稿", 0.95);

            Map<String, List<String>> synonymDict = new HashMap<>();
            synonymDict.put("讲稿", List.of("底稿", "草稿"));

            when(configLoader.getFixedMapping()).thenReturn(fixedMapping);
            when(configLoader.getFixedMappingConfidence()).thenReturn(confidenceMap);
            when(configLoader.getSynonymDict()).thenReturn(synonymDict);

            QueryRewriteResult result = queryRewriteService.rewrite("请帮我找讲稿");

            // 扩展关键词应包含同义词（因为 query.contains("讲稿") 为 true）
            assertThat(result.getExpandKeywords()).contains("底稿", "草稿");
        }
    }

    // ==================== 场景2：长词包含短词 ====================

    @Nested
    @DisplayName("场景2：长词包含短词（如\"API网关\"与\"API\"）")
    class LongWordContainsShortWord {

        @Test
        @DisplayName("长词优先匹配：\"API网关\"应整体替换，\"API\"不应误伤")
        void longWordPriorityOverShortWord() {
            Map<String, String> fixedMapping = new HashMap<>();
            fixedMapping.put("API", "API 接口");
            fixedMapping.put("API网关", "API Gateway");

            Map<String, Double> confidenceMap = new HashMap<>();
            confidenceMap.put("API", 0.90);
            confidenceMap.put("API网关", 0.95);

            when(configLoader.getFixedMapping()).thenReturn(fixedMapping);
            when(configLoader.getFixedMappingConfidence()).thenReturn(confidenceMap);

            QueryRewriteResult result = queryRewriteService.rewrite("如何配置API网关");

            // "API网关" 应整体替换为 "API Gateway"，"API" 不应单独替换
            assertThat(result.getRewrittenQuery()).contains("API Gateway");
            assertThat(result.getRewrittenQuery()).doesNotContain("API 接口");
        }

        @Test
        @DisplayName("短词在长词之外独立出现时仍应被替换")
        void shortWordStillReplacedWhenIndependent() {
            Map<String, String> fixedMapping = new HashMap<>();
            fixedMapping.put("API", "API 接口");
            fixedMapping.put("API网关", "API Gateway");

            Map<String, Double> confidenceMap = new HashMap<>();
            confidenceMap.put("API", 0.90);
            confidenceMap.put("API网关", 0.95);

            when(configLoader.getFixedMapping()).thenReturn(fixedMapping);
            when(configLoader.getFixedMappingConfidence()).thenReturn(confidenceMap);

            // "API" 独立出现在句尾，"API网关" 出现在句中
            QueryRewriteResult result = queryRewriteService.rewrite("API网关和API的区别");

            assertThat(result.getRewrittenQuery()).contains("API Gateway");
            assertThat(result.getRewrittenQuery()).contains("API 接口");
        }

        @Test
        @DisplayName("同义词长词优先：长同义词应优先于短同义词匹配")
        void synonymLongWordPriority() {
            Map<String, List<String>> synonymDict = new HashMap<>();
            synonymDict.put("数据库", List.of("DB", "Database"));
            synonymDict.put("数据库连接", List.of("DB连接", "数据库链接"));

            when(configLoader.getSynonymDict()).thenReturn(synonymDict);

            QueryRewriteResult result = queryRewriteService.rewrite("数据库连接超时");

            // "数据库连接" 应整体匹配，"数据库" 不应单独匹配
            assertThat(result.getRewrittenQuery()).contains("数据库连接 DB连接");
        }
    }

    // ==================== 场景3：多个不重叠的同义词 ====================

    @Nested
    @DisplayName("场景3：多个不重叠的同义词")
    class MultipleNonOverlappingSynonyms {

        @Test
        @DisplayName("查询中包含多个不重叠的同义词时，应全部展开")
        void allNonOverlappingSynonymsExpanded() {
            Map<String, List<String>> synonymDict = new HashMap<>();
            synonymDict.put("薪资", List.of("工资", "薪酬"));
            synonymDict.put("配置", List.of("设置", "参数"));

            when(configLoader.getSynonymDict()).thenReturn(synonymDict);
            // L2 分词 mock：返回包含同义词 key 的分词结果
            when(tokenizerService.tokenize(anyString(), anyBoolean())).thenReturn(List.of("薪资", "配置", "方案"));
            when(tokenizerService.extractKeywords(anyString(), anyInt())).thenReturn(List.of("薪资", "配置", "方案"));

            QueryRewriteResult result = queryRewriteService.rewrite("薪资配置方案");

            // 两个同义词都应被展开
            assertThat(result.getExpandKeywords()).contains("工资", "薪酬", "设置", "参数");
        }

        @Test
        @DisplayName("同义词展开后扩展关键词应包含所有同义词值")
        void expandKeywordsContainAllSynonymValues() {
            Map<String, List<String>> synonymDict = new HashMap<>();
            synonymDict.put("换工作", List.of("跳槽", "转行", "职业转换"));

            when(configLoader.getSynonymDict()).thenReturn(synonymDict);
            // L2 分词 mock
            when(tokenizerService.tokenize(anyString(), anyBoolean())).thenReturn(List.of("换工作", "注意事项"));
            when(tokenizerService.extractKeywords(anyString(), anyInt())).thenReturn(List.of("换工作", "注意事项"));

            QueryRewriteResult result = queryRewriteService.rewrite("换工作注意事项");

            // expandKeywords 包含同义词值 + L1/L2 提取的关键词，只验证同义词值存在
            assertThat(result.getExpandKeywords()).contains("跳槽", "转行", "职业转换");
        }

        @Test
        @DisplayName("同义词 key 等于第一个同义词值时应跳过该同义词替换")
        void synonymSkippedWhenKeyEqualsFirstValue() {
            Map<String, List<String>> synonymDict = new HashMap<>();
            // key 和第一个同义词相同，应跳过同义词替换
            synonymDict.put("测试", List.of("测试"));

            when(configLoader.getSynonymDict()).thenReturn(synonymDict);

            QueryRewriteResult result = queryRewriteService.rewrite("测试用例");

            // 不应有同义词替换（因为 key == firstSynonym 被跳过），但 L2 会提取关键词
            assertThat(result.getRewrittenQuery()).contains("测试用例");
            // 同义词替换被跳过，但 L2 NLP 提取了关键词，置信度 > 0.50
            assertThat(result.getConfidence()).isGreaterThan(0.50);
        }
    }

    // ==================== 场景4：排除词提取 ====================

    @Nested
    @DisplayName("场景4：排除词提取")
    class ExcludeKeywordExtraction {

        @Test
        @DisplayName("\"不包含\" 模式应正确提取排除词")
        void extractExcludeKeywordWithNotContain() {
            QueryRewriteResult result = queryRewriteService.rewrite("查询方法不包含删除");

            assertThat(result.getExcludeKeywords()).contains("删除");
        }

        @Test
        @DisplayName("\"除了\" 模式应正确提取排除词")
        void extractExcludeKeywordWithExcept() {
            QueryRewriteResult result = queryRewriteService.rewrite("查询方法除了新增");

            assertThat(result.getExcludeKeywords()).contains("新增");
        }

        @Test
        @DisplayName("\"不要\" 模式应正确提取排除词")
        void extractExcludeKeywordWithDontWant() {
            QueryRewriteResult result = queryRewriteService.rewrite("推荐书籍不要小说");

            assertThat(result.getExcludeKeywords()).contains("小说");
        }

        @Test
        @DisplayName("\"排除\" 模式应正确提取排除词")
        void extractExcludeKeywordWithExclude() {
            QueryRewriteResult result = queryRewriteService.rewrite("搜索结果排除广告");

            assertThat(result.getExcludeKeywords()).contains("广告");
        }

        @Test
        @DisplayName("\"不含\" 模式应正确提取排除词")
        void extractExcludeKeywordWithWithout() {
            // 修复后：按助词分割，"糖的饮料" → "糖" + "饮料"，"糖" 长度1被过滤，"饮料" 保留
            QueryRewriteResult result = queryRewriteService.rewrite("查找不含糖的饮料");

            assertThat(result.getExcludeKeywords()).contains("饮料");
        }

        @Test
        @DisplayName("多个排除词用顿号分隔时应全部提取")
        void multipleExcludeKeywordsWithDunHao() {
            QueryRewriteResult result = queryRewriteService.rewrite("查询方法不包含删除、修改");

            assertThat(result.getExcludeKeywords()).containsExactly("删除", "修改");
        }

        @Test
        @DisplayName("多个排除词用逗号和\"和\"分隔时应全部提取")
        void multipleExcludeKeywordsWithCommaAndHe() {
            QueryRewriteResult result = queryRewriteService.rewrite("推荐不包含小说和诗歌");

            assertThat(result.getExcludeKeywords()).containsExactly("小说", "诗歌");
        }

        @Test
        @DisplayName("无排除词模式时排除词列表应为空")
        void noExcludeKeywordsWhenNoPattern() {
            QueryRewriteResult result = queryRewriteService.rewrite("查询方法列表");

            assertThat(result.getExcludeKeywords()).isEmpty();
        }

        @Test
        @DisplayName("排除词长度小于2时应被过滤")
        void shortExcludeKeywordsFiltered() {
            // "删" 和 "改" 长度都为1，截断助词后仍为1，应被过滤
            QueryRewriteResult result = queryRewriteService.rewrite("不包含删、改");

            assertThat(result.getExcludeKeywords()).isEmpty();
        }
    }

    // ==================== 场景5：L2 继承 L1 结果 ====================

    @Nested
    @DisplayName("场景5：L2 继承 L1 结果")
    class L2InheritsL1Result {

        @Test
        @DisplayName("L2 的 rewrittenQuery 应基于 L1 的改写结果")
        void l2QueryBasedOnL1Rewrite() {
            Map<String, String> fixedMapping = new HashMap<>();
            fixedMapping.put("讲稿", "讲义");

            Map<String, Double> confidenceMap = new HashMap<>();
            confidenceMap.put("讲稿", 0.60); // 低置信度，确保 L1 不满足阈值，进入 L2

            when(configLoader.getFixedMapping()).thenReturn(fixedMapping);
            when(configLoader.getFixedMappingConfidence()).thenReturn(confidenceMap);

            QueryRewriteResult result = queryRewriteService.rewrite("查找讲稿内容");

            // L2 应基于 L1 的改写结果 "查找讲义内容"，而非原始查询
            assertThat(result.getRewrittenQuery()).contains("讲义");
            assertThat(result.getPath()).isEqualTo(RewritePath.L2_NLP);
        }

        @Test
        @DisplayName("L2 的 expandKeywords 应合并 L1 的扩展关键词和 L2 新提取的关键词")
        void l2MergesL1AndNewKeywords() {
            Map<String, List<String>> synonymDict = new HashMap<>();
            synonymDict.put("薪资", List.of("工资", "薪酬"));

            when(configLoader.getSynonymDict()).thenReturn(synonymDict);
            // L2 分词 mock
            when(tokenizerService.tokenize(anyString(), anyBoolean())).thenReturn(List.of("薪资", "调整", "方案"));
            when(tokenizerService.extractKeywords(anyString(), anyInt())).thenReturn(List.of("薪资", "调整", "方案"));

            // 使用 RULE_ONLY 路由，确保走 L2
            RewriteRequest request = RewriteRequest.builder()
                    .query("薪资调整方案")
                    .routingDecision(RoutingDecision.RULE_ONLY)
                    .build();

            QueryRewriteResult result = queryRewriteService.rewrite(request);

            // L1 的扩展关键词（同义词）+ L2 分词同义词扩展应出现在 expandKeywords 中
            assertThat(result.getExpandKeywords()).contains("工资", "薪酬");
            assertThat(result.getPath()).isEqualTo(RewritePath.L2_NLP);
        }

        @Test
        @DisplayName("L2 的 excludeKeywords 应继承 L1 的排除词")
        void l2InheritsL1ExcludeKeywords() {
            RewriteRequest request = RewriteRequest.builder()
                    .query("查询方法不包含删除")
                    .routingDecision(RoutingDecision.RULE_ONLY)
                    .build();

            QueryRewriteResult result = queryRewriteService.rewrite(request);

            // L2 应继承 L1 提取的排除词
            assertThat(result.getExcludeKeywords()).contains("删除");
            assertThat(result.getPath()).isEqualTo(RewritePath.L2_NLP);
        }

        @Test
        @DisplayName("L2 置信度低于 L2 阈值且 L3 可用时应尝试 L3")
        void l2LowConfidenceFallsBackToL3() {
            ReflectionTestUtils.setField(queryRewriteService, "llmRewriter", llmRewriter);
            // 提高 L2 阈值，使 L2 置信度不满足阈值，触发 L3
            ReflectionTestUtils.setField(queryRewriteService, "l2ConfidenceThreshold", 0.90);

            // 无固定映射和同义词，L1 置信度低
            QueryRewriteResult l3Result = QueryRewriteResult.builder()
                    .rewrittenQuery("如何进行数据库连接配置")
                    .expandKeywords(List.of("数据库", "连接", "配置"))
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.90)
                    .path(RewritePath.L3_CONTEXT_COMPLETION)
                    .build();

            when(llmRewriter.rewrite(anyString())).thenReturn(l3Result);

            RewriteRequest request = RewriteRequest.builder()
                    .query("数据库连接")
                    .routingDecision(RoutingDecision.FULL)
                    .build();

            QueryRewriteResult result = queryRewriteService.rewrite(request);

            assertThat(result.getPath()).isEqualTo(RewritePath.L3_CONTEXT_COMPLETION);
            assertThat(result.getRewrittenQuery()).isEqualTo("如何进行数据库连接配置");
        }

        @Test
        @DisplayName("L3 抛异常时应降级使用 L2 结果")
        void l3ExceptionFallsBackToL2() {
            ReflectionTestUtils.setField(queryRewriteService, "llmRewriter", llmRewriter);
            // 提高 L2 阈值使 L2 不满足，才会触发 L3 调用
            ReflectionTestUtils.setField(queryRewriteService, "l2ConfidenceThreshold", 0.90);

            when(llmRewriter.rewrite(anyString())).thenThrow(new RuntimeException("LLM 服务不可用"));

            RewriteRequest request = RewriteRequest.builder()
                    .query("数据库连接")
                    .routingDecision(RoutingDecision.FULL)
                    .build();

            QueryRewriteResult result = queryRewriteService.rewrite(request);

            // 应降级到 L2
            assertThat(result.getPath()).isEqualTo(RewritePath.L2_NLP);
        }

        @Test
        @DisplayName("L3 返回未改写结果时应降级使用 L2 结果")
        void l3NotRewrittenFallsBackToL2() {
            ReflectionTestUtils.setField(queryRewriteService, "llmRewriter", llmRewriter);
            // 提高 L2 阈值使 L2 不满足，才会触发 L3 调用
            ReflectionTestUtils.setField(queryRewriteService, "l2ConfidenceThreshold", 0.90);

            // L3 返回 NONE 路径（未改写）
            QueryRewriteResult l3Result = QueryRewriteResult.builder()
                    .rewrittenQuery("数据库连接")
                    .expandKeywords(Collections.emptyList())
                    .excludeKeywords(Collections.emptyList())
                    .confidence(0.0)
                    .path(RewritePath.NONE)
                    .build();

            when(llmRewriter.rewrite(anyString())).thenReturn(l3Result);

            RewriteRequest request = RewriteRequest.builder()
                    .query("数据库连接")
                    .routingDecision(RoutingDecision.FULL)
                    .build();

            QueryRewriteResult result = queryRewriteService.rewrite(request);

            assertThat(result.getPath()).isEqualTo(RewritePath.L2_NLP);
        }
    }

    // ==================== 边界场景补充 ====================

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("改写禁用时应直接返回原始查询")
        void disabledReturnsOriginalQuery() {
            ReflectionTestUtils.setField(queryRewriteService, "enabled", false);

            QueryRewriteResult result = queryRewriteService.rewrite("测试查询");

            assertThat(result.getRewrittenQuery()).isEqualTo("测试查询");
            assertThat(result.getPath()).isEqualTo(RewritePath.NONE);
            assertThat(result.getConfidence()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("null 查询应返回空字符串")
        void nullQueryReturnsEmpty() {
            QueryRewriteResult result = queryRewriteService.rewrite((String) null);

            assertThat(result.getRewrittenQuery()).isEmpty();
            assertThat(result.getPath()).isEqualTo(RewritePath.NONE);
        }

        @Test
        @DisplayName("空白查询应返回空字符串和 NONE 路径")
        void blankQueryReturnsEmpty() {
            QueryRewriteResult result = queryRewriteService.rewrite("   ");

            assertThat(result.getRewrittenQuery()).isEmpty();
            assertThat(result.getPath()).isEqualTo(RewritePath.NONE);
        }

        @Test
        @DisplayName("SKIP 路由决策应跳过改写")
        void skipDecisionBypassesRewrite() {
            RewriteRequest request = RewriteRequest.builder()
                    .query("测试查询")
                    .routingDecision(RoutingDecision.SKIP)
                    .build();

            QueryRewriteResult result = queryRewriteService.rewrite(request);

            assertThat(result.getRewrittenQuery()).isEqualTo("测试查询");
            assertThat(result.getPath()).isEqualTo(RewritePath.NONE);
        }

        @Test
        @DisplayName("L1 高置信度时应直接返回 L1 结果，不进入 L2")
        void l1HighConfidenceSkipsL2() {
            Map<String, String> fixedMapping = new HashMap<>();
            fixedMapping.put("专升本", "专升本 3500词");

            Map<String, Double> confidenceMap = new HashMap<>();
            confidenceMap.put("专升本", 0.95);

            when(configLoader.getFixedMapping()).thenReturn(fixedMapping);
            when(configLoader.getFixedMappingConfidence()).thenReturn(confidenceMap);

            QueryRewriteResult result = queryRewriteService.rewrite("专升本考试");

            assertThat(result.getPath()).isEqualTo(RewritePath.L1_RULE);
            assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.85);
        }

        @Test
        @DisplayName("同义词区间不应与固定映射区间重叠")
        void synonymIntervalNotOverlapWithFixedMapping() {
            Map<String, String> fixedMapping = new HashMap<>();
            fixedMapping.put("API网关", "API Gateway");

            Map<String, Double> confidenceMap = new HashMap<>();
            confidenceMap.put("API网关", 0.95);

            Map<String, List<String>> synonymDict = new HashMap<>();
            synonymDict.put("API", List.of("接口", "interface"));

            when(configLoader.getFixedMapping()).thenReturn(fixedMapping);
            when(configLoader.getFixedMappingConfidence()).thenReturn(confidenceMap);
            when(configLoader.getSynonymDict()).thenReturn(synonymDict);

            QueryRewriteResult result = queryRewriteService.rewrite("API网关配置");

            // "API网关" 被固定映射整体替换，"API" 不应再单独替换（区间重叠）
            assertThat(result.getRewrittenQuery()).contains("API Gateway");
            assertThat(result.getRewrittenQuery()).doesNotContain("API 接口");
        }
    }
}
