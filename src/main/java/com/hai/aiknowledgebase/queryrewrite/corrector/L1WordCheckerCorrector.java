package com.hai.aiknowledgebase.queryrewrite.corrector;

import com.github.houbb.word.checker.util.WordCheckerHelper;
import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * L1 纠错层：基于 word-checker 内置词典的拼写检查
 *
 * <h3>策略</h3>
 * word-checker 自带 27W+ 英文词典 + 中文词典，无需外部字典。
 * <ol>
 *   <li>分词后逐 token 调用 word-checker 拼写检查</li>
 *   <li>word-checker 判定为拼写错误的 token，获取候选纠正列表</li>
 *   <li>候选词与原词编辑距离 ≤ 1 的视为有效纠错</li>
 *   <li>置信度 = 正确 token 数 / 总 token 数</li>
 * </ol>
 *
 * <h3>性能</h3>
 * 纯内存操作，耗时 < 5ms，超时阈值 50ms。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class L1WordCheckerCorrector implements CorrectorLayer {

    private final ChineseTokenizerService tokenizerService;

    /**
     * 预热 word-checker 内置词典（27W+ 中英文词典）
     *
     * <p>word-checker 的词典在首次调用 {@link WordCheckerHelper#isCorrect} 时懒加载，
     * 耗时可能超过 L1 超时阈值（50ms）。通过启动时触发一次加载，
     * 确保请求到来时词典已在内存中，避免首次请求因词典加载超时而降级到 L2。</p>
     */
    @PostConstruct
    public void warmUp() {
        long start = System.currentTimeMillis();
        WordCheckerHelper.isCorrect("预热");
        long costMs = System.currentTimeMillis() - start;
        log.info("word-checker 词典预热完成，耗时: {}ms", costMs);
    }

    @Override
    public String getLayerName() {
        return "L1";
    }

    @Override
    public CorrectionResult correct(String query) {
        long start = System.currentTimeMillis();

        if (query == null || query.isBlank()) {
            return CorrectionResult.noCorrection(query != null ? query : "", getLayerName());
        }

        List<String> tokens = tokenizerService.tokenize(query, false);
        if (tokens.isEmpty()) {
            return CorrectionResult.noCorrection(query, getLayerName());
        }

        StringBuilder corrected = new StringBuilder(query);
        int offset = 0;
        List<CorrectionDetail> details = new ArrayList<>();
        int totalTokens = tokens.size();
        int failedTokens = 0;

        for (String token : tokens) {
            // word-checker 内置词典判定
            if (WordCheckerHelper.isCorrect(token)) {
                continue;
            }

            String bestMatch = findBestCandidate(token);
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
                            .reason("编辑距离")
                            .build());
                }
            } else {
                failedTokens++;
            }
        }

        String result = corrected.toString();
        boolean hasCorrection = !result.equals(query);

        double confidence;
        if (!hasCorrection) {
            confidence = 1.0;
        } else {
            confidence = (double) (totalTokens - failedTokens) / totalTokens;
        }

        long costMs = System.currentTimeMillis() - start;
        log.debug("L1 纠错完成 | 原始: {} | 纠错后: {} | 置信度: {} | 耗时: {}ms",
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
     * 从 word-checker 候选列表中找最优匹配
     */
    private String findBestCandidate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        List<String> candidates = WordCheckerHelper.correctList(token, 5);
        for (String candidate : candidates) {
            if (candidate.equals(token)) {
                continue;
            }
            int dist = editDistance(token, candidate);
            if (dist <= 1) {
                return candidate;
            }
        }
        return null;
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