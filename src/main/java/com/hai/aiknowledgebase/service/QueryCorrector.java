package com.hai.aiknowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 查询纠错服务（Query Corrector）
 *
 * <h2>功能概述</h2>
 * 基于同义词词典 + 编辑距离（Levenshtein Distance）实现轻量级查询纠错。
 * 对用户输入的查询进行分词后，逐一检查每个 token 是否在词典中；
 * 若不在，则计算该 token 与所有词典词的编辑距离，找出距离 ≤ 1 的最佳匹配并替换。
 *
 * <h2>纠错流程</h2>
 * <ol>
 *   <li><b>构建候选词典</b>：合并同义词词典的所有 key/value 和固定映射的所有 key</li>
 *   <li><b>分词</b>：调用 {@link ChineseTokenizerService#tokenize(String, boolean)} 对查询分词</li>
 *   <li><b>逐词检查</b>：对每个 token 判断是否已在词典中</li>
 *   <li><b>编辑距离匹配</b>：对不在词典中的 token，计算与所有候选词的编辑距离，
 *       若存在距离 ≤ 1 的候选词，则替换为词典词</li>
 *   <li><b>记录状态</b>：通过 {@link ThreadLocal} 记录本次是否发生了纠错，
 *       供 {@link #hasCorrection()} 查询</li>
 * </ol>
 *
 * <h2>使用场景</h2>
 * 在 {@link QueryRewriteService} 的 L2 NLP 增强阶段被调用，
 * 纠错后的查询用于后续的意图识别和查询改写。
 *
 * <h2>设计说明</h2>
 * <ul>
 *   <li>编辑距离阈值设为 1，仅纠正单字符错误（删除、插入、替换各一个字符）</li>
 *   <li>使用 {@link ThreadLocal} 存储纠错状态，保证线程安全</li>
 *   <li>词典每次从 {@link QueryRewriteConfigLoader} 实时获取，支持热加载</li>
 *   <li>对于长度差异 > 1 的候选词直接跳过，避免不必要的编辑距离计算</li>
 * </ul>
 *
 * <h2>注意事项</h2>
 * <ul>
 *   <li>token 替换使用 {@link String#indexOf(String, int)} 定位，
 *       当同一 token 在查询中出现多次时，按从左到右顺序替换</li>
 *   <li>编辑距离计算使用 O(n) 空间优化的 Levenshtein 算法，仅保留两行 DP 数组</li>
 *   <li>候选词典使用 {@link LinkedHashSet} 保证插入顺序，确保 {@link #findBestMatch}
 *       在多个候选词编辑距离相同时返回确定性结果</li>
 * </ul>
 *
 * @see QueryRewriteConfigLoader 词典配置加载器
 * @see ChineseTokenizerService 中文分词服务
 * @see QueryRewriteService 查询改写服务（调用方）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryCorrector {

    /**
     * 查询改写配置加载器，提供同义词词典和固定映射词典。
     * 支持定时热加载，因此每次 {@link #correct(String)} 调用都实时获取最新词典。
     */
    private final QueryRewriteConfigLoader configLoader;

    /**
     * 中文分词服务，用于将查询文本切分为 token 列表。
     * 调用 {@link ChineseTokenizerService#tokenize(String, boolean)} 方法，
     * 第二个参数为 false 表示不启用全模式分词。
     */
    private final ChineseTokenizerService tokenizerService;

    /**
     * 线程隔离的纠错状态标记
     * <p>
     * 使用 {@link ThreadLocal} 存储当前线程最近一次 {@link #correct(String)}
     * 调用是否发生了 token 替换。该状态由 {@link #correct(String)} 在每次调用开始时重置，
     * 由 {@link #hasCorrection()} 读取（纯查询，不修改状态）。
     * <p>
     * <b>设计原因：</b>在 {@link QueryRewriteService} 中，纠错和改写是两个独立步骤，
     * 需要通过此标记传递"是否发生了纠错"这一信息，便于后续步骤做决策。
     * ThreadLocal 保证了多线程并发调用时的隔离性。
     */
    private final ThreadLocal<Boolean> lastCorrectionHappened = new ThreadLocal<>();

    /**
     * 对查询进行纠错（核心方法）
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>重置状态</b>：将 {@link #lastCorrectionHappened} 设为 false</li>
     *   <li><b>空值检查</b>：null 返回空字符串，空白字符串原样返回</li>
     *   <li><b>构建候选词典</b>：调用 {@link #buildDictKeySet()} 获取所有候选词</li>
     *   <li><b>分词</b>：调用 {@link ChineseTokenizerService#tokenize(String, boolean)}
     *       对查询分词（非全模式）</li>
     *   <li><b>逐词纠错</b>：遍历每个 token，若不在词典中，
     *       调用 {@link #findBestMatch(String, Set)} 查找编辑距离 ≤ 1 的候选词并替换</li>
     *   <li><b>返回结果</b>：返回纠错后的查询文本（可能和原始查询相同）</li>
     * </ol>
     *
     * <h3>替换策略</h3>
     * 使用 {@link StringBuilder} 在原始查询文本上进行原地替换。
     * 通过 offset 变量追踪替换位置，确保多次替换时位置正确。
     * 但需注意：当替换后的词长度不同于原词长度时，offset 追踪可能出现偏差。
     *
     * <h3>副作用</h3>
     * 每次调用都会更新 {@link #lastCorrectionHappened} 的值，
     * 该值可通过 {@link #hasCorrection()} 查询。
     *
     * @param query 原始查询文本，可能为 null 或空字符串
     * @return 纠错后的查询文本。如果未发生纠错，返回原始查询；
     *         null 输入返回空字符串 ""
     */
    public String correct(String query) {
        // 重置当前线程的纠错状态标记
        lastCorrectionHappened.set(false);

        // null → ""，空白字符串 → 原样返回
        if (query == null || query.isBlank()) {
            return query != null ? query : "";
        }

        // 构建候选词典：同义词 key/value + 固定映射 key
        Set<String> dictKeys = buildDictKeySet();
        if (dictKeys.isEmpty()) {
            return query;  // 词典为空，无法纠错，直接返回
        }

        // 对查询进行分词（非全模式，精确切分）
        java.util.List<String> tokens = tokenizerService.tokenize(query, false);
        if (tokens.isEmpty()) {
            return query;  // 分词结果为空，无法纠错
        }

        // 使用 StringBuilder 进行原地替换，避免频繁创建新字符串
        StringBuilder corrected = new StringBuilder(query);
        int offset = 0;  // 追踪替换后的偏移位置

        for (String token : tokens) {
            // 如果词已在词典中，说明是正确的，无需纠错
            if (dictKeys.contains(token)) {
                continue;
            }

            // 查找编辑距离 ≤ 1 的词典词作为最佳匹配
            String bestMatch = findBestMatch(token, dictKeys);
            if (bestMatch != null) {
                // 在原始查询中定位 token 的位置
                String original = corrected.toString();
                int idx = original.indexOf(token, offset);
                if (idx >= 0) {
                    // 原地替换：将错误的 token 替换为正确的词典词
                    corrected.replace(idx, idx + token.length(), bestMatch);
                    // 更新 offset，跳过已替换的部分，继续向后搜索
                    offset = idx + bestMatch.length();
                    lastCorrectionHappened.set(true);
                    log.debug("查询纠错: '{}' → '{}' (编辑距离 ≤ 1)", token, bestMatch);
                }
            }
        }

        return corrected.toString();
    }

    /**
     * 判断最近一次 {@link #correct(String)} 调用是否发生了纠错
     *
     * <h3>使用方式</h3>
     * 必须先调用 {@link #correct(String)} 执行纠错，再调用本方法查询状态。
     * 典型用法：
     * <pre>{@code
     * String corrected = corrector.correct(query);
     * if (corrector.hasCorrection()) {
     *     // 处理纠错后的逻辑
     * }
     * }</pre>
     *
     * <h3>设计说明</h3>
     * 此方法为纯查询方法，无副作用。不调用 {@link #correct(String)}，
     * 不修改 {@link #lastCorrectionHappened} 的状态。
     * 状态由 {@link #correct(String)} 在每次调用开始时重置，
     * 因此多次调用 {@code hasCorrection()} 始终返回相同结果（针对同一线程的最后一次 correct 调用）。
     *
     * @return true 表示最近一次 correct 调用中发生了 token 替换
     */
    public boolean hasCorrection() {
        Boolean result = lastCorrectionHappened.get();
        return result != null && result;
    }

    /**
     * 构建候选词典 key 集合
     *
     * <h3>数据来源</h3>
     * 候选词典由以下两部分合并而成：
     * <ol>
     *   <li><b>同义词词典</b>（{@link QueryRewriteConfigLoader#getSynonymDict()}）：
     *       包含所有 key 和所有 value（同义词列表中的每个词）</li>
     *   <li><b>固定映射</b>（{@link QueryRewriteConfigLoader#getFixedMapping()}）：
     *       包含所有 key（如 "API" → "API 接口" 中的 "API"）</li>
     * </ol>
     *
     * <h3>确定性保证</h3>
     * 使用 {@link LinkedHashSet} 而非 {@code HashSet}，保证插入顺序不变。
     * 这使得 {@link #findBestMatch(String, Set)} 在多个候选词编辑距离相同时，
     * 返回的结果是可预测的（优先返回先插入词典的词）。
     *
     * <h3>性能考量</h3>
     * 每次调用都会创建新的 {@link LinkedHashSet} 并遍历所有词典条目。
     * 在词典规模较大或高并发场景下，可考虑添加缓存（如 Caffeine Cache）
     * 并监听 {@link QueryRewriteConfigLoader} 的热加载事件来刷新缓存。
     *
     * @return 所有候选词的集合（保证插入顺序），词典为空时返回空集合
     */
    private Set<String> buildDictKeySet() {
        Set<String> keys = new LinkedHashSet<>();

        // 1. 收集同义词词典的所有词（key + 所有 value）
        Map<String, java.util.List<String>> synonymDict = configLoader.getSynonymDict();
        if (synonymDict != null) {
            keys.addAll(synonymDict.keySet());
            // 同义词的 value 列表中的每个词也加入候选集
            for (java.util.List<String> values : synonymDict.values()) {
                if (values != null) {
                    keys.addAll(values);
                }
            }
        }

        // 2. 收集固定映射的所有 key
        Map<String, String> fixedMapping = configLoader.getFixedMapping();
        if (fixedMapping != null) {
            keys.addAll(fixedMapping.keySet());
        }

        return keys;
    }

    /**
     * 查找与给定词编辑距离 ≤ 1 的最佳匹配候选词
     *
     * <h3>匹配策略</h3>
     * <ol>
     *   <li><b>长度预过滤</b>：候选词与目标词长度差 > 1 的直接跳过，
     *       因为编辑距离 ≤ 1 要求长度差 ≤ 1</li>
     *   <li><b>编辑距离计算</b>：对通过预过滤的候选词，
     *       调用 {@link #editDistance(String, String)} 计算 Levenshtein 距离</li>
     *   <li><b>最小距离选取</b>：记录距离最小的候选词，阈值 minDist=2 表示仅接受距离 ≤ 1</li>
     * </ol>
     *
     * <h3>注意事项</h3>
     * 当多个候选词与目标词的编辑距离相同（都是 1）时，返回第一个遍历到的候选词。
     * 由于候选集基于 {@link HashSet}，迭代顺序不确定，
     * 因此相同输入在不同 JVM 运行中可能返回不同的候选词。
     * 如需确定性行为，可改用 {@link java.util.LinkedHashSet} 或添加二级排序规则。
     *
     * @param word    待匹配的错误词（来自分词结果）
     * @param dictKey 词典候选词集合
     * @return 编辑距离 ≤ 1 的最佳匹配词，若没有则返回 null
     */
    private String findBestMatch(String word, Set<String> dictKey) {
        String bestMatch = null;
        int minDist = 2; // 初始化为 2，只接受编辑距离 ≤ 1 的匹配

        for (String key : dictKey) {
            // 长度差异 > 1 的候选词不可能编辑距离 ≤ 1，直接跳过以节省计算
            if (Math.abs(key.length() - word.length()) > 1) {
                continue;
            }

            int dist = editDistance(word, key);
            if (dist < minDist) {
                minDist = dist;
                bestMatch = key;
                // 距离为 0 表示完全匹配，但这种情况理论上不会出现
                // （因为调用方已通过 dictKeys.contains(token) 过滤了已在词典中的词）
            }
        }

        return bestMatch;
    }

    /**
     * 计算两个字符串的编辑距离（Levenshtein Distance）
     *
     * <h3>算法说明</h3>
     * Levenshtein 距离定义为：将一个字符串转换为另一个字符串所需的最少
     * 单字符编辑操作次数（插入、删除、替换各计 1 次）。
     *
     * <h3>空间优化</h3>
     * 标准 DP 需要 O(m×n) 的二维数组。本实现使用滚动数组优化，
     * 仅保留两行（prev 和 curr），空间复杂度为 O(min(m, n))。
     *
     * <h3>时间复杂度</h3>
     * O(m × n)，其中 m 和 n 分别为两个字符串的长度。
     * 对于中文纠错场景（单个词通常 ≤ 10 个字符），性能可接受。
     *
     * <h3>DP 状态转移</h3>
     * <pre>
     * if s1[i-1] == s2[j-1]:
     *     dp[i][j] = dp[i-1][j-1]          // 字符相同，无需操作
     * else:
     *     dp[i][j] = 1 + min(
     *         dp[i-1][j],    // 删除 s1[i-1]
     *         dp[i][j-1],    // 插入 s2[j-1]
     *         dp[i-1][j-1]   // 替换 s1[i-1] → s2[j-1]
     *     )
     * </pre>
     *
     * @param s1 第一个字符串
     * @param s2 第二个字符串
     * @return 两个字符串之间的编辑距离（≥ 0 的整数）
     */
    private int editDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();

        // 滚动数组：prev 表示上一行，curr 表示当前行
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        // 初始化第一行：空字符串 → s2 的编辑距离 = j
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }

        // 逐行填充 DP 表
        for (int i = 1; i <= m; i++) {
            curr[0] = i;  // 第一列：s1 → 空字符串的编辑距离 = i
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    // 字符相同，继承左上角的值
                    curr[j] = prev[j - 1];
                } else {
                    // 取删除、插入、替换三种操作的最小值 + 1
                    curr[j] = 1 + Math.min(Math.min(prev[j], curr[j - 1]), prev[j - 1]);
                }
            }
            // 交换行引用：下一轮迭代中 prev 变为当前行，curr 复用为新的当前行
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return prev[n];  // 最终结果在 prev 的最后位置
    }
}