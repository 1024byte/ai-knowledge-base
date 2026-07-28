package com.hai.aiknowledgebase.queryrewrite.corrector;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * L3 纠错层：基于 LLM 语义理解的纠错（兜底）
 *
 * <h3>策略</h3>
 * 当 L1（word-checker）和 L2（拼音匹配）都无法高置信度纠正时，
 * 由 LLM 根据语义上下文进行最终纠错。LLM 能理解上下文，区分"错别字"和"不同词"。
 *
 * <h3>Prompt 设计</h3>
 * 约束 LLM 只纠正明显的错别字，不修改正确的专业术语，只返回纠错后文本。
 *
 * <h3>容错</h3>
 * 超时 2000ms，失败返回原始查询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class L3LLMCorrector implements CorrectorLayer {

    private final OpenAiChatModel chatModel;

    @Override
    public String getLayerName() {
        return "L3";
    }

    @Override
    public CorrectionResult correct(String query) {
        long start = System.currentTimeMillis();

        if (query == null || query.isBlank()) {
            return CorrectionResult.noCorrection(query != null ? query : "", getLayerName());
        }

        try {
            String corrected = correctByLLM(query);
            long costMs = System.currentTimeMillis() - start;
            boolean hasCorrection = corrected != null && !corrected.equals(query);

            if (hasCorrection) {
                log.info("L3 纠错命中 | 原始: {} | 纠错后: {} | 耗时: {}ms", query, corrected, costMs);
                return CorrectionResult.builder()
                        .originalQuery(query)
                        .correctedQuery(corrected.trim())
                        .confidence(0.60)
                        .layer(getLayerName())
                        .corrected(true)
                        .costMs(costMs)
                        .details(Collections.singletonList(CorrectionDetail.builder()
                                .original(query)
                                .corrected(corrected.trim())
                                .editDistance(-1)
                                .reason("LLM语义")
                                .build()))
                        .build();
            }

            return CorrectionResult.noCorrection(query, getLayerName());
        } catch (Exception e) {
            log.error("L3 LLM 纠错异常: {}", e.getMessage());
            return CorrectionResult.noCorrection(query, getLayerName());
        }
    }

    private String correctByLLM(String query) {
        String prompt = String.format("""
                你是一个拼写纠错助手。请检查用户查询中的错别字并纠正。

                规则：
                1. 只纠正明显的错别字（如"存诸"→"存储"、"相量"→"向量"、"持久花"→"持久化"）。
                2. 不要修改正确的专业术语（如"EmbeddingStore"、"Redis"、"向量检索"等）。
                3. 如果查询中没有错别字，原样返回。
                4. 只返回纠正后的查询文本，不要任何解释、前缀或标点。

                用户查询：%s
                纠正后：""", query);

        return chatModel.chat(List.of(
                SystemMessage.from("你是一个拼写纠错助手。只返回纠正后的查询文本，不要任何解释。"),
                UserMessage.from(prompt)
        )).aiMessage().text();
    }
}