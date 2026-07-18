package com.hai.aiknowledgebase.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 查询纠错服务
 * <p>
 * 基于同义词词典和编辑距离实现简单的查询纠错：
 * 对用户输入的每个词，检查是否在同义词词典或固定映射的 key 中，
 * 如果不在，计算与词典 key 的编辑距离，
 * 如果最小编辑距离 ≤ 1 且目标词在词典中，则替换为词典词。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryCorrector {

    private final QueryRewriteConfigLoader configLoader;
    private final ChineseTokenizerService tokenizerService;

    /** 上次纠错是否发生了替换 */
    private final ThreadLocal<Boolean> lastCorrectionHappened = new ThreadLocal<>();

    /**
     * 对查询进行纠错
     *
     * @param query 原始查询
     * @return 纠错后的查询文本
     */
    public String correct(String query) {
        lastCorrectionHappened.set(false);

        if (query == null || query.isBlank()) {
            return query != null ? query : "";
        }

        // 收集所有词典 key 作为候选集
        Set<String> dictKeys = buildDictKeySet();
        if (dictKeys.isEmpty()) {
            return query;
        }

        // 对查询进行分词
        java.util.List<String> tokens = tokenizerService.tokenize(query, false);
        if (tokens.isEmpty()) {
            return query;
        }

        StringBuilder corrected = new StringBuilder(query);
        int offset = 0;

        for (String token : tokens) {
            // 如果词已在词典中，无需纠错
            if (dictKeys.contains(token)) {
                continue;
            }

            // 查找编辑距离 ≤ 1 的词典词
            String bestMatch = findBestMatch(token, dictKeys);
            if (bestMatch != null) {
                // 替换查询中的该词
                String original = corrected.toString();
                int idx = original.indexOf(token, offset);
                if (idx >= 0) {
                    corrected.replace(idx, idx + token.length(), bestMatch);
                    offset = idx + bestMatch.length();
                    lastCorrectionHappened.set(true);
                    log.debug("查询纠错: '{}' → '{}' (编辑距离 ≤ 1)", token, bestMatch);
                }
            }
        }

        return corrected.toString();
    }

    /**
     * 判断最近一次 correct 调用是否发生了纠错
     *
     * @return true 如果发生了纠错
     */
    public boolean hasCorrection(String query) {
        // 先执行一次纠错以确保状态正确
        correct(query);
        Boolean result = lastCorrectionHappened.get();
        lastCorrectionHappened.remove();
        return result != null && result;
    }

    /**
     * 构建词典 key 集合（同义词 key + 固定映射 key）
     */
    private Set<String> buildDictKeySet() {
        Set<String> keys = new java.util.HashSet<>();

        Map<String, java.util.List<String>> synonymDict = configLoader.getSynonymDict();
        if (synonymDict != null) {
            keys.addAll(synonymDict.keySet());
            // 也加入同义词值
            for (java.util.List<String> values : synonymDict.values()) {
                if (values != null) {
                    keys.addAll(values);
                }
            }
        }

        Map<String, String> fixedMapping = configLoader.getFixedMapping();
        if (fixedMapping != null) {
            keys.addAll(fixedMapping.keySet());
        }

        return keys;
    }

    /**
     * 查找与给定词编辑距离 ≤ 1 的最佳匹配
     *
     * @param word    待匹配词
     * @param dictKey 词典 key 集合
     * @return 最佳匹配词，如果没有则返回 null
     */
    private String findBestMatch(String word, Set<String> dictKey) {
        String bestMatch = null;
        int minDist = 2; // 只接受编辑距离 ≤ 1

        for (String key : dictKey) {
            // 长度差异 > 1 的不可能编辑距离 ≤ 1
            if (Math.abs(key.length() - word.length()) > 1) {
                continue;
            }

            int dist = editDistance(word, key);
            if (dist < minDist) {
                minDist = dist;
                bestMatch = key;
            }
        }

        return bestMatch;
    }

    /**
     * 计算两个字符串的编辑距离（Levenshtein Distance）
     */
    private int editDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();

        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    curr[j] = prev[j - 1];
                } else {
                    curr[j] = 1 + Math.min(Math.min(prev[j], curr[j - 1]), prev[j - 1]);
                }
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return prev[n];
    }
}
