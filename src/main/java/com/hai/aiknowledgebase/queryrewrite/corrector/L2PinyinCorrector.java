package com.hai.aiknowledgebase.queryrewrite.corrector;

import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import com.hai.aiknowledgebase.service.QueryRewriteConfigLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * L2 纠错层：基于拼音匹配 + 编辑距离的混合纠错
 *
 * <h3>核心策略</h3>
 * 中文错别字绝大多数是同音字（如"存诸"→"存储"、"相量"→"向量"），
 * 纯字符编辑距离无法区分"同音错别字"和"不同词"（如"动词"vs"单词"距离都是1）。
 * 本层引入拼音匹配，当两个词拼音相近时判定为错别字，否则跳过。
 *
 * <h3>纠错流程</h3>
 * <ol>
 *   <li>分词 + 词典检查（同原有 QueryCorrector）</li>
 *   <li>不在词典中的 token → 字符编辑距离匹配（编辑距离 ≤ 1）</li>
 *   <li>字符编辑距离无法匹配 → 拼音匹配（拼音编辑距离 ≤ 1）</li>
 *   <li>两轮都失败 → 标记为无法纠正</li>
 * </ol>
 *
 * <h3>置信度计算</h3>
 * 对每个 token 打分后取平均。拼音匹配命中的 token 置信度 0.80，
 * 字符编辑距离命中的 0.85，无法纠正的 0.50。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class L2PinyinCorrector implements CorrectorLayer {

    private final ChineseTokenizerService tokenizerService;
    private final QueryRewriteConfigLoader configLoader;

    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = new HanyuPinyinOutputFormat();

    static {
        PINYIN_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    @Override
    public String getLayerName() {
        return "L2";
    }

    @Override
    public CorrectionResult correct(String query) {
        long start = System.currentTimeMillis();

        if (query == null || query.isBlank()) {
            return CorrectionResult.noCorrection(query != null ? query : "", getLayerName());
        }

        Set<String> dictKeys = configLoader.getAllDictionaryKeys();
        if (dictKeys.isEmpty()) {
            return CorrectionResult.noCorrection(query, getLayerName());
        }

        List<String> tokens = tokenizerService.tokenize(query, false);
        if (tokens.isEmpty()) {
            return CorrectionResult.noCorrection(query, getLayerName());
        }

        StringBuilder corrected = new StringBuilder(query);
        int offset = 0;
        List<CorrectionDetail> details = new ArrayList<>();
        int totalTokens = tokens.size();
        double totalScore = 0.0;

        for (String token : tokens) {
            if (dictKeys.contains(token)) {
                totalScore += 1.0;
                continue;
            }

            String bestMatch = null;
            String reason = null;
            double tokenScore = 0.50;

            // 第1轮：字符编辑距离匹配
            String charMatch = findByCharEditDistance(token, dictKeys);
            if (charMatch != null) {
                bestMatch = charMatch;
                reason = "编辑距离";
                tokenScore = 0.85;
            }

            // 第2轮：拼音匹配（仅中文 token）
            if (bestMatch == null && containsChinese(token)) {
                String pinyinMatch = findByPinyin(token, dictKeys);
                if (pinyinMatch != null) {
                    bestMatch = pinyinMatch;
                    reason = "同音字";
                    tokenScore = 0.80;
                }
            }

            if (bestMatch != null) {
                String current = corrected.toString();
                int idx = current.indexOf(token, offset);
                if (idx >= 0) {
                    corrected.replace(idx, idx + token.length(), bestMatch);
                    offset = idx + bestMatch.length();
                    details.add(CorrectionDetail.builder()
                            .original(token)
                            .corrected(bestMatch)
                            .editDistance(editDistance(token, bestMatch))
                            .reason(reason)
                            .build());
                }
            }

            totalScore += tokenScore;
        }

        String result = corrected.toString();
        boolean hasCorrection = !result.equals(query);
        double confidence = totalScore / totalTokens;

        long costMs = System.currentTimeMillis() - start;
        log.debug("L2 纠错完成 | 原始: {} | 纠错后: {} | 置信度: {} | 耗时: {}ms",
                query, result, confidence, costMs);

        return CorrectionResult.builder()
                .originalQuery(query)
                .correctedQuery(result)
                .confidence(confidence)
                .layer(getLayerName())
                .corrected(hasCorrection)
                .costMs(costMs)
                .details(details)
                .build();
    }

    /**
     * 字符编辑距离匹配
     */
    private String findByCharEditDistance(String token, Set<String> dictKeys) {
        String bestMatch = null;
        int minDist = containsChinese(token) ? 2 : 2;

        for (String key : dictKeys) {
            if (Math.abs(key.length() - token.length()) >= minDist) {
                continue;
            }
            int dist = editDistance(token, key);
            if (dist < minDist) {
                minDist = dist;
                bestMatch = key;
            }
        }

        return minDist <= 1 ? bestMatch : null;
    }

    /**
     * 拼音匹配：将 token 和词典词转换为拼音后比较
     *
     * <p>拼音编辑距离 ≤ 1 视为同音错别字候选。
     * 例如："存诸"(cun zhu) vs "存储"(cun chu) → 拼音距离=1 → 匹配</p>
     */
    private String findByPinyin(String token, Set<String> dictKeys) {
        String tokenPinyin = toPinyin(token);
        if (tokenPinyin == null || tokenPinyin.isEmpty()) {
            return null;
        }

        String bestMatch = null;
        int minDist = Integer.MAX_VALUE;

        for (String key : dictKeys) {
            if (Math.abs(key.length() - token.length()) > 2) {
                continue;
            }
            String keyPinyin = toPinyin(key);
            if (keyPinyin == null || keyPinyin.isEmpty()) {
                continue;
            }

            int dist = editDistance(tokenPinyin, keyPinyin);
            if (dist <= 1 && dist < minDist) {
                minDist = dist;
                bestMatch = key;
            }
        }

        return bestMatch;
    }

    /**
     * 将中文文本转换为拼音字符串（无音调，空格分隔）
     */
    private String toPinyin(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 0x4e00 && c <= 0x9fff) {
                try {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c, PINYIN_FORMAT);
                    if (pinyinArray != null && pinyinArray.length > 0) {
                        if (sb.length() > 0) {
                            sb.append(" ");
                        }
                        sb.append(pinyinArray[0]);
                    }
                } catch (BadHanyuPinyinOutputFormatCombination e) {
                    // 忽略
                }
            } else {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private boolean containsChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) {
                return true;
            }
        }
        return false;
    }

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