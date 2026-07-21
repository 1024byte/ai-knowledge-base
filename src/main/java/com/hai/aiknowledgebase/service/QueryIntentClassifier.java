package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.dto.IntentResult;
import com.hai.aiknowledgebase.dto.QueryIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 查询意图分类器（Query Intent Classifier）
 *
 * <h2>功能概述</h2>
 * 基于规则引擎 + 关键词匹配的轻量级意图识别服务，不依赖 LLM（大语言模型），
 * 用于在 L2 NLP 处理阶段指导查询改写策略和置信度计算。
 *
 * <h2>分类体系</h2>
 * 本分类器将用户查询分为以下 5 种意图类型：
 * <ul>
 *   <li><b>DEFINITIONAL（定义查询）</b>：用户询问某个概念的含义或定义，如"什么是向量召回"</li>
 *   <li><b>PROCEDURAL（操作步骤查询）</b>：用户询问如何操作、步骤方法，如"如何配置API接口"</li>
 *   <li><b>COMPARISON（对比查询）</b>：用户希望比较两个事物的异同，如"MySQL和PostgreSQL的区别"</li>
 *   <li><b>FACTUAL（事实查询）</b>：用户询问具体数值、是非判断等事实性问题，如"专升本需要多少词汇量"</li>
 *   <li><b>AMBIGUOUS（模糊查询）</b>：查询中指代不明、关键词过少，无法确定具体意图，如"那个东西"</li>
 * </ul>
 *
 * <h2>分类优先级（从高到低）</h2>
 * <ol>
 *   <li>DEFINITIONAL — "什么是"等模式最为明确，优先匹配</li>
 *   <li>PROCEDURAL — "如何"、"怎么"等操作类关键词</li>
 *   <li>COMPARISON — "区别"、"对比"等比较类关键词；"和"/"与"需额外验证上下文</li>
 *   <li>FACTUAL — "多少"、"是否"等疑问类关键词</li>
 *   <li>AMBIGUOUS — 代词占比过高或有效关键词过少</li>
 * </ol>
 *
 * <h2>使用场景</h2>
 * 在 {@link QueryRewriteService} 的 L2 NLP 增强阶段被调用，
 * 分类结果用于选择不同的改写策略（如 PROCEDURAL 查询会增强操作动词的同义词扩展）。
 *
 * <h2>设计说明</h2>
 * <ul>
 *   <li>纯规则驱动，无外部 API 调用，响应时间在毫秒级</li>
 *   <li>关键词集合为静态常量，线程安全，无状态服务</li>
 *   <li>依赖 {@link ChineseTokenizerService} 仅在判断 AMBIGUOUS 时用于分词计数</li>
 * </ul>
 *
 * @see QueryIntent 意图枚举定义
 * @see ChineseTokenizerService 中文分词服务
 * @see QueryRewriteService 查询改写服务（调用方）
 * @deprecated 请使用 {@link IntentRecognitionOrchestrator#recognize(String)} 替代。
 *             本类保留以兼容旧代码，内部委托给新编排器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Deprecated
public class QueryIntentClassifier {

    private final ChineseTokenizerService tokenizerService;
    private final IntentRecognitionOrchestrator orchestrator;

    // ==================== 意图关键词定义 ====================

    private static final Set<String> FACTUAL_KEYWORDS = Set.of(
            "多少", "几个", "是否", "能不能", "可以", "有多少", "几", "多久", "什么时候"
    );

    private static final Set<String> PROCEDURAL_KEYWORDS = Set.of(
            "如何", "怎么", "怎样", "步骤", "方法", "操作", "教程", "怎么做", "如何做", "怎么办"
    );

    private static final Set<String> COMPARISON_KEYWORDS = Set.of(
            "区别", "对比", "不同", "差异", "vs", "VS", "和", "与", "相比", "比较"
    );

    private static final Set<String> DEFINITIONAL_KEYWORDS = Set.of(
            "什么是", "定义", "含义", "意思", "是什么", "指的是", "概念", "解释"
    );

    private static final Set<String> AMBIGUOUS_PRONOUNS = Set.of(
            "那个", "这个", "它", "这", "那", "东西", "玩意"
    );

    private static final Pattern ZH_SEQ_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    /**
     * 对用户查询进行意图分类（核心方法）
     *
     * <h3>分类流程（按优先级顺序）</h3>
     * <ol>
     *   <li><b>空值检查</b>：null 或空白字符串直接返回 AMBIGUOUS</li>
     *   <li><b>DEFINITIONAL</b>：检查是否包含定义类关键词</li>
     *   <li><b>PROCEDURAL</b>：检查是否包含操作类关键词</li>
     *   <li><b>COMPARISON</b>：检查是否包含对比类关键词</li>
     *   <li><b>FACTUAL</b>：检查是否包含事实类疑问词</li>
     *   <li><b>AMBIGUOUS</b>：判断代词占比或关键词数量</li>
     *   <li><b>默认 FACTUAL</b>：以上均不匹配时，默认归为事实查询</li>
     * </ol>
     *
     * @param query 用户输入的原始查询文本，可能为 null 或空字符串
     * @return 识别出的意图类型，保证非 null
     * @deprecated 请使用 {@link IntentRecognitionOrchestrator#recognize(String)}
     *             获取更丰富的 {@link IntentResult}（含置信度和改写提示）
     */
    @Deprecated
    public QueryIntent classify(String query) {
        // 委托给新编排器，提取主意图
        IntentResult result = orchestrator.recognize(query);
        return result.primaryIntent();
    }

    /**
     * 判断"和"/"与"是否为对比连接词
     */
    private boolean isComparisonConjunction(String query, String conjunction) {
        int idx = query.indexOf(conjunction);
        if (idx <= 0 || idx + conjunction.length() >= query.length()) {
            return false;
        }
        String before = query.substring(0, idx);
        String after = query.substring(idx + conjunction.length());
        return ZH_SEQ_PATTERN.matcher(before).find()
                && ZH_SEQ_PATTERN.matcher(after).find();
    }

    /**
     * 判断查询是否属于模糊查询
     */
    private boolean isAmbiguous(String query) {
        int pronounCount = 0;
        for (String pronoun : AMBIGUOUS_PRONOUNS) {
            int fromIndex = 0;
            while ((fromIndex = query.indexOf(pronoun, fromIndex)) != -1) {
                pronounCount += pronoun.length();
                fromIndex += pronoun.length();
            }
        }
        if (query.length() > 0 && (double) pronounCount / query.length() > 0.3) {
            return true;
        }
        List<String> tokens = tokenizerService.tokenize(query);
        return tokens.size() <= 1;
    }
}
