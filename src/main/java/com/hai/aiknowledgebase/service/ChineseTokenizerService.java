package com.hai.aiknowledgebase.service;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 中文分词服务
 * <p>
 * 基于 jieba-analysis 实现中文分词，提供搜索引擎模式（细粒度）和精确模式（粗粒度）两种分词策略。
 * 搜索引擎模式用于 BM25 关键词扩展，精确模式用于向量查询改写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChineseTokenizerService {

    private final QueryRewriteConfigLoader configLoader;

    private static final Pattern EN_WORD_PATTERN = Pattern.compile("[a-zA-Z0-9_]+");
    private static final Pattern ZH_SEQ_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    private volatile JiebaSegmenter segmenter;

    private JiebaSegmenter getSegmenter() {
        if (segmenter == null) {
            synchronized (this) {
                if (segmenter == null) {
                    segmenter = new JiebaSegmenter();
                    log.info("JiebaSegmenter 初始化完成");
                }
            }
        }
        return segmenter;
    }

    /**
     * 分词（搜索引擎模式 - 细粒度切分），过滤停用词和单字
     *
     * @param text 待分词文本
     * @return 分词结果列表
     */
    public List<String> tokenize(String text) {
        return tokenize(text, true);
    }

    /**
     * 分词，过滤停用词和单字
     *
     * @param text           待分词文本
     * @param searchMode     true=搜索引擎模式（细粒度），false=精确模式（粗粒度）
     * @return 分词结果列表
     */
    public List<String> tokenize(String text, boolean searchMode) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();

        // 提取英文/数字词
        Matcher enMatcher = EN_WORD_PATTERN.matcher(text);
        while (enMatcher.find()) {
            String word = enMatcher.group();
            if (word.length() >= 2 && !configLoader.isStopWord(word)) {
                result.add(word);
            }
        }

        // 提取中文序列并分词
        Matcher zhMatcher = ZH_SEQ_PATTERN.matcher(text);
        while (zhMatcher.find()) {
            String zhSeq = zhMatcher.group();
            List<SegToken> tokens;
            if (searchMode) {
                tokens = getSegmenter().process(zhSeq, JiebaSegmenter.SegMode.SEARCH);
            } else {
                tokens = getSegmenter().process(zhSeq, JiebaSegmenter.SegMode.INDEX);
            }
            for (SegToken token : tokens) {
                String word = token.word.trim();
                if (word.length() >= 2 && !configLoader.isStopWord(word)) {
                    result.add(word);
                }
            }
        }

        return result;
    }

    /**
     * 分词 + 词性标注（为后续意图识别提供基础）
     * <p>
     * jieba-analysis 不直接提供词性标注，此处返回分词结果及简单词性推断
     *
     * @param text 待分词文本
     * @return 分词结果列表（含词性信息）
     */
    public List<TokenWithPos> tokenizeWithPos(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<TokenWithPos> result = new ArrayList<>();

        // 英文/数字词
        Matcher enMatcher = EN_WORD_PATTERN.matcher(text);
        while (enMatcher.find()) {
            String word = enMatcher.group();
            if (word.length() >= 2 && !configLoader.isStopWord(word)) {
                result.add(new TokenWithPos(word, inferPosEn(word)));
            }
        }

        // 中文分词
        Matcher zhMatcher = ZH_SEQ_PATTERN.matcher(text);
        while (zhMatcher.find()) {
            String zhSeq = zhMatcher.group();
            List<SegToken> tokens = getSegmenter().process(zhSeq, JiebaSegmenter.SegMode.SEARCH);
            for (SegToken token : tokens) {
                String word = token.word.trim();
                if (word.length() >= 2 && !configLoader.isStopWord(word)) {
                    result.add(new TokenWithPos(word, inferPosZh(word)));
                }
            }
        }

        return result;
    }

    /**
     * 提取 Top-N 关键词（基于 TF-IDF 思路，结合词频和词长加权）
     *
     * @param text 待提取文本
     * @param topN 返回前 N 个关键词
     * @return 关键词列表
     */
    public List<String> extractKeywords(String text, int topN) {
        List<String> tokens = tokenize(text, true);
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }

        // 词频统计
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String token : tokens) {
            freq.put(token, freq.getOrDefault(token, 0) + 1);
        }

        // 加权排序：词频 * 词长权重（长词权重更高）
        return freq.entrySet().stream()
                .sorted((e1, e2) -> {
                    double score1 = e1.getValue() * (1 + e1.getKey().length() * 0.3);
                    double score2 = e2.getValue() * (1 + e2.getKey().length() * 0.3);
                    return Double.compare(score2, score1);
                })
                .limit(topN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    // ==================== 简单词性推断 ====================

    private String inferPosEn(String word) {
        if (word.matches("\\d+")) return "NUM";
        return "ENG";
    }

    private String inferPosZh(String word) {
        // 简单基于规则推断词性
        if (word.matches(".*[如何怎么怎样哪什么谁几].*")) return "PRON";
        if (word.matches(".*[的得了着过地得].*")) return "AUX";
        if (word.matches(".*[不没无非].*")) return "NEG";
        return "NOUN";
    }

    // ==================== 内部类 ====================

    /**
     * 带词性的分词结果
     */
    public static class TokenWithPos {
        private final String word;
        private final String pos;

        public TokenWithPos(String word, String pos) {
            this.word = word;
            this.pos = pos;
        }

        public String getWord() {
            return word;
        }

        public String getPos() {
            return pos;
        }
    }
}
