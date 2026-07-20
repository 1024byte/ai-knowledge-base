package com.hai.aiknowledgebase.service;

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
 *   <li>FACTUAL（默认兜底）— 无任何特殊意图标记时，默认归为事实查询</li>
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryIntentClassifier {

    /**
     * 中文分词服务，用于在判断模糊查询时对查询文本进行分词，
     * 以评估有效关键词数量。仅在 {@link #isAmbiguous(String)} 中调用。
     */
    private final ChineseTokenizerService tokenizerService;

    // ==================== 意图关键词定义 ====================

    /**
     * 事实查询关键词集合
     * <p>
     * 匹配这些关键词的查询通常是在询问具体的数值、是非判断或条件判断。
     * 例如："多少"（数量询问）、"是否"（是非判断）、"什么时候"（时间询问）、
     * "可以"（能力/许可判断）。
     * <p>
     * <b>注意：</b>"可以"关键词范围较宽泛，可能误匹配非事实类查询，
     * 但由于其优先级排在 DEFINITIONAL / PROCEDURAL / COMPARISON 之后，
     * 实际影响有限。
     */
    private static final Set<String> FACTUAL_KEYWORDS = Set.of(
            "多少", "几个", "是否", "能不能", "可以", "有多少", "几", "多久", "什么时候"
    );

    /**
     * 操作步骤查询关键词集合
     * <p>
     * 匹配这些关键词的查询通常是在询问操作方法、流程步骤或教程指南。
     * 例如："如何"（方式询问）、"步骤"（流程询问）、"教程"（学习资源询问）。
     * <p>
     * 这类查询在 L2 改写阶段会增强操作动词的同义词扩展。
     */
    private static final Set<String> PROCEDURAL_KEYWORDS = Set.of(
            "如何", "怎么", "怎样", "步骤", "方法", "操作", "教程", "怎么做", "如何做", "怎么办"
    );

    /**
     * 对比查询关键词集合
     * <p>
     * 匹配这些关键词的查询通常是在比较两个或多个事物的异同。
     * 例如："区别"（差异询问）、"对比"（比较请求）、"vs"（英文对比缩写）。
     * <p>
     * <b>特殊处理：</b>"和"与"与"这两个连接词需要额外通过
     * {@link #isComparisonConjunction(String, String)} 验证上下文，
     * 确保它们确实在连接两个名词性成分（而非简单的并列或其它用法）。
     */
    private static final Set<String> COMPARISON_KEYWORDS = Set.of(
            "区别", "对比", "不同", "差异", "vs", "VS", "和", "与", "相比", "比较"
    );

    /**
     * 定义查询关键词集合
     * <p>
     * 匹配这些关键词的查询通常是在询问某个术语或概念的定义、含义。
     * 例如："什么是"（定义询问）、"定义"（术语解释）、"指的是"（指代澄清）。
     * <p>
     * <b>优先级最高：</b>这类查询的模式最为明确，在分类时优先匹配。
     */
    private static final Set<String> DEFINITIONAL_KEYWORDS = Set.of(
            "什么是", "定义", "含义", "意思", "是什么", "指的是", "概念", "解释"
    );

    /**
     * 模糊指示代词集合
     * <p>
     * 这些词在查询中表示指代不明、缺乏具体指向。
     * 当这些代词在查询文本中占比超过 30% 时，查询将被判定为 AMBIGUOUS。
     * <p>
     * 例如："那个东西"、"这个玩意" 等完全由代词构成的查询。
     */
    private static final Set<String> AMBIGUOUS_PRONOUNS = Set.of(
            "那个", "这个", "它", "这", "那", "东西", "玩意"
    );

    /**
     * 中文字符序列匹配正则
     * <p>
     * 用于在 {@link #isComparisonConjunction(String, String)} 中
     * 判断连接词前后是否存在中文内容，以验证是否为有效的对比结构。
     * <p>
     * Unicode 范围：CJK 统一表意文字（U+4E00 ~ U+9FA5）
     */
    private static final Pattern ZH_SEQ_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    /**
     * 对用户查询进行意图分类（核心方法）
     *
     * <h3>分类流程（按优先级顺序）</h3>
     * <ol>
     *   <li><b>空值检查</b>：null 或空白字符串直接返回 AMBIGUOUS</li>
     *   <li><b>DEFINITIONAL</b>：检查是否包含定义类关键词（"什么是"、"定义"等），
     *       优先级最高，因为这类模式最为明确</li>
     *   <li><b>PROCEDURAL</b>：检查是否包含操作类关键词（"如何"、"怎么"、"步骤"等）</li>
     *   <li><b>COMPARISON</b>：检查是否包含对比类关键词。其中"和"/"与"两个连接词
     *       需要额外调用 {@link #isComparisonConjunction(String, String)} 验证上下文，
     *       确保它们确实在连接两个名词性成分</li>
     *   <li><b>FACTUAL</b>：检查是否包含事实类疑问词（"多少"、"是否"等）</li>
     *   <li><b>AMBIGUOUS</b>：调用 {@link #isAmbiguous(String)} 判断代词占比或关键词数量</li>
     *   <li><b>默认 FACTUAL</b>：以上均不匹配时，默认归为事实查询</li>
     * </ol>
     *
     * <h3>匹配策略</h3>
     * 使用 {@link String#contains(CharSequence)} 进行简单子串匹配，
     * 而非精确分词匹配。这意味着关键词可能作为子串出现在更大的词中。
     * 例如查询"什么是"会同时匹配"什么"和"什么是"，但由于迭代顺序和
     * 优先级设计，实际匹配结果由首次命中决定。
     *
     * @param query 用户输入的原始查询文本，可能为 null 或空字符串
     * @return 识别出的意图类型，保证非 null
     */
    public QueryIntent classify(String query) {
        // 空值与空白字符串直接判定为模糊查询，无需后续处理
        if (query == null || query.isBlank()) {
            return QueryIntent.AMBIGUOUS;
        }

        // 去除首尾空白，避免空格干扰关键词匹配
        String trimmed = query.trim();

        // ===== 第1步：检查 DEFINITIONAL（优先级最高） =====
        // "什么是"、"定义"等模式最为明确，用户意图无可争议，优先匹配
        for (String keyword : DEFINITIONAL_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                log.debug("意图识别: DEFINITIONAL (匹配关键词: {})", keyword);
                return QueryIntent.DEFINITIONAL;
            }
        }

        // ===== 第2步：检查 PROCEDURAL（操作步骤类） =====
        // "如何"、"怎么"等明确表示用户在寻求操作方法
        for (String keyword : PROCEDURAL_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                log.debug("意图识别: PROCEDURAL (匹配关键词: {})", keyword);
                return QueryIntent.PROCEDURAL;
            }
        }

        // ===== 第3步：检查 COMPARISON（对比类） =====
        // 注意："和"/"与" 是普通连接词，需要额外验证上下文是否为对比结构
        for (String keyword : COMPARISON_KEYWORDS) {
            if ("和".equals(keyword) || "与".equals(keyword)) {
                // 连接词需要前后都有名词性成分才判定为对比意图
                if (isComparisonConjunction(trimmed, keyword)) {
                    log.debug("意图识别: COMPARISON (匹配连接词: {})", keyword);
                    return QueryIntent.COMPARISON;
                }
            } else {
                // 其他对比关键词（"区别"、"对比"、"vs"等）直接匹配
                if (trimmed.contains(keyword)) {
                    log.debug("意图识别: COMPARISON (匹配关键词: {})", keyword);
                    return QueryIntent.COMPARISON;
                }
            }
        }

        // ===== 第4步：检查 FACTUAL（事实类） =====
        // "多少"、"是否"、"什么时候"等疑问词
        for (String keyword : FACTUAL_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                log.debug("意图识别: FACTUAL (匹配关键词: {})", keyword);
                return QueryIntent.FACTUAL;
            }
        }

        // ===== 第5步：检查 AMBIGUOUS（模糊查询） =====
        // 代词占比过高（>30%）或有效关键词过少（<=1个）
        if (isAmbiguous(trimmed)) {
            log.debug("意图识别: AMBIGUOUS (代词占比高或关键词过少)");
            return QueryIntent.AMBIGUOUS;
        }

        // ===== 第6步：默认兜底为 FACTUAL =====
        // 查询有明确内容但无任何特殊意图标记（如"API配置说明"），默认归为事实查询
        log.debug("意图识别: FACTUAL (默认)");
        return QueryIntent.FACTUAL;
    }

    /**
     * 判断 "和" 或 "与" 在查询中是否作为对比连接词使用
     *
     * <h3>背景</h3>
     * "和" 和 "与" 是中文中极为常见的连接词，它们可能出现在多种语境中：
     * <ul>
     *   <li><b>对比语境</b>：如"MySQL和PostgreSQL的区别"，此时应判定为 COMPARISON</li>
     *   <li><b>普通并列</b>：如"安装和配置"，此时不应判定为对比</li>
     *   <li><b>复合词组成部分</b>：如"和平"、"参与"，此时更不应判定为对比</li>
     * </ul>
     * 因此需要额外的上下文验证来区分这些场景。
     *
     * <h3>验证规则</h3>
     * 对查询中所有匹配位置进行遍历，每个位置经过两层校验：
     * <ol>
     *   <li><b>独立性校验</b>：通过 {@link #isStandaloneOccurrence(String, String, int)}
     *       检查该位置是否为独立连接词（非复合词组成部分），
     *       排除如"维护和平"、"参与活动"中的"和"/"与"</li>
     *   <li><b>前后实体校验</b>：检查连接词前后是否都存在内容（中文序列或英文/数字），
     *       确保连接词在连接两个独立的实体概念</li>
     * </ol>
     * 任意一个匹配位置通过两层校验即返回 true，所有位置均不通过则返回 false。
     *
     * @param query       用户查询文本（已去除首尾空白）
     * @param conjunction 待检查的连接词，值为 "和" 或 "与"
     * @return true 表示该连接词在对比语境中使用；false 表示不符合对比条件
     */
    private boolean isComparisonConjunction(String query, String conjunction) {
        int idx = 0;
        // 遍历查询中所有匹配位置，而非仅检查第一个
        while ((idx = query.indexOf(conjunction, idx)) >= 0) {
            // 第1层：独立性校验 —— 排除复合词中的"和"/"与"
            if (isStandaloneOccurrence(query, conjunction, idx)) {
                // 第2层：前后实体校验 —— 确保连接的是两个独立概念
                String before = query.substring(0, idx);
                String after = query.substring(idx + conjunction.length());

                boolean hasNounBefore = ZH_SEQ_PATTERN.matcher(before).find() || before.matches(".*[a-zA-Z0-9].*");
                boolean hasNounAfter = ZH_SEQ_PATTERN.matcher(after).find() || after.matches(".*[a-zA-Z0-9].*");

                if (hasNounBefore && hasNounAfter) {
                    return true;
                }
            }
            idx++;
        }
        return false;
    }

    /**
     * 判断连接词在指定位置是否为独立出现（非复合词组成部分）
     *
     * <h3>判定逻辑</h3>
     * 检查连接词前后紧邻的字符是否为 CJK 统一表意文字（U+4E00 ~ U+9FA5）。
     * 如果前后两侧紧邻字符都是 CJK 字符，则"和"/"与"很可能是一个
     * 复合词（如"维护和平"、"参与活动"）的组成部分，而非独立的对比连接词。
     *
     * <h3>示例</h3>
     * <ul>
     *   <li>"维护和平"中"和"的前后是"护"和"平"（都是 CJK）→ 非独立，返回 false</li>
     *   <li>"MySQL和PostgreSQL"中"和"的前后是'L'和'P'（非 CJK）→ 独立，返回 true</li>
     *   <li>"和平共处"中"和"在开头，后邻"平"（CJK）→ 仅一侧 CJK，视为独立，返回 true
     *       （但后续前后实体校验中 before 为空，hasNounBefore=false，最终仍会排除）</li>
     * </ul>
     *
     * @param query       用户查询文本
     * @param conjunction 连接词
     * @param idx         连接词在查询中的起始位置
     * @return true 表示该位置的连接词是独立出现的；false 表示是复合词的一部分
     */
    private boolean isStandaloneOccurrence(String query, String conjunction, int idx) {
        char before = idx > 0 ? query.charAt(idx - 1) : 0;
        char after = idx + conjunction.length() < query.length() ? query.charAt(idx + conjunction.length()) : 0;

        boolean beforeIsCjk = before >= 0x4E00 && before <= 0x9FA5;
        boolean afterIsCjk = after >= 0x4E00 && after <= 0x9FA5;

        // 前后都是 CJK 字符 → 极可能是复合词的一部分，排除
        return !(beforeIsCjk && afterIsCjk);
    }

    /**
     * 判断查询是否属于模糊查询（AMBIGUOUS）
     *
     * <h3>判定条件（满足任一即判定为模糊）</h3>
     * <ol>
     *   <li><b>代词占比过高</b>：
     *       遍历 {@link #AMBIGUOUS_PRONOUNS} 集合，计算查询中包含的指示代词
     *       总字符数占查询总长度的比例。若超过 30%，则判定为模糊查询。
     *       <p>
     *       例如："那个东西"（4个字符中"那个东西"全是代词，占比100%）→ AMBIGUOUS
     *       </p>
     *   </li>
     *   <li><b>有效关键词过少</b>：
     *       调用 {@link ChineseTokenizerService#tokenize(String)} 对查询进行分词，
     *       若分词结果数量 ≤ 1，则判定为模糊查询。
     *       <p>
     *       例如："查"（分词后可能只有1个或0个有效词）→ AMBIGUOUS
     *       </p>
     *   </li>
     * </ol>
     *
     * <h3>设计考量</h3>
     * 两个条件采用"或"逻辑，任何一个触发都会将查询标记为 AMBIGUOUS。
     * 这可能导致一些边缘情况被过度判定为模糊，但避免了将真正模糊的查询
     * 误判为具体意图，属于偏保守的策略。
     *
     * @param query 用户查询文本（已去除首尾空白，保证非空）
     * @return true 表示查询模糊，无法确定具体意图；false 表示查询有明确内容
     */
    private boolean isAmbiguous(String query) {
        // ===== 条件1：代词占比检查 =====
        // 计算查询中所有指示代词的总字符数
        long pronounChars = AMBIGUOUS_PRONOUNS.stream()
                .filter(query::contains)
                .mapToLong(String::length)
                .sum();

        // 代词字符数占查询总长度的比例超过 30% 则判定为模糊
        if (query.length() > 0 && (double) pronounChars / query.length() > 0.3) {
            return true;
        }

        // ===== 条件2：有效关键词数量检查 =====
        // 通过分词服务获取有效关键词列表
        List<String> tokens = tokenizerService.tokenize(query);
        // 分词结果 ≤ 1 个有效词，说明查询信息量不足
        if (tokens.size() <= 1) {
            return true;
        }

        return false;
    }
}