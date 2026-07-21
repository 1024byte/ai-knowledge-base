package com.hai.aiknowledgebase.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hai.aiknowledgebase.dto.IntentResult;
import com.hai.aiknowledgebase.dto.IntentResult.IntentCandidate;
import com.hai.aiknowledgebase.dto.QueryIntent;
import com.hai.aiknowledgebase.interfaces.IntentClassifier;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 LangChain4j LLM 的意图分类器
 *
 * <h2>功能概述</h2>
 * 使用大语言模型进行语义级别的意图识别，能够处理规则引擎无法覆盖的
 * 复杂查询、隐含意图和边界情况。作为策略链的"慢路径"兜底。
 *
 * <h2>执行策略</h2>
 * <ul>
 *   <li>仅在规则引擎返回低置信度时被调用（由编排器控制）</li>
 *   <li>使用 DeepSeek API（通过 OpenAiChatModel 兼容接口）进行意图分类</li>
 *   <li>LLM 返回结构化 JSON，包含主意图、置信度、备选意图和改写提示</li>
 *   <li>失败时自动降级，不阻塞主流程</li>
 * </ul>
 *
 * <h2>Prompt 设计</h2>
 * 使用 System Prompt 定义分类体系，User Prompt 仅传入查询文本。
 * 要求 LLM 返回紧凑 JSON，减少 token 消耗。
 *
 * <h2>容错机制</h2>
 * <ul>
 *   <li>LLM 调用超时/失败 → 返回 AMBIGUOUS + 置信度 0.0</li>
 *   <li>JSON 解析失败 → 返回 AMBIGUOUS + 置信度 0.0</li>
 *   <li>未知意图类型 → 降级为 AMBIGUOUS</li>
 * </ul>
 *
 * @see IntentClassifier 策略接口
 * @see RuleIntentClassifier 规则引擎（快路径）
 * @see IntentRecognitionOrchestrator 编排器
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class LLMIntentClassifier implements IntentClassifier {

    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper;

    /**
     * System Prompt：定义分类体系和输出格式
     * <p>
     * 采用紧凑 JSON 格式，减少 token 消耗。
     * 要求 LLM 同时输出改写提示（rewriteHints），
     * 用于指导查询改写服务进行精准扩展。
     */
    private static final String SYSTEM_PROMPT = """
            你是一个查询意图分类器。分析用户查询，返回 JSON（不要 Markdown 代码块）：
            {"primaryIntent":"DEFINITIONAL|PROCEDURAL|COMPARISON|FACTUAL|AMBIGUOUS","confidence":0.0~1.0,"alternatives":[{"intent":"...","probability":0.0}],"rewriteHints":["提示词"]}

            意图说明：
            - DEFINITIONAL：询问概念定义/含义，如"什么是向量召回"
            - PROCEDURAL：询问操作步骤/方法/教程，如"如何配置API"
            - COMPARISON：对比两个事物，如"MySQL和PostgreSQL的区别"
            - FACTUAL：询问具体事实/数值/是非，如"专升本需要多少词汇量"
            - AMBIGUOUS：指代不明、关键词过少，无法确定意图

            rewriteHints：给出2-5个可用于查询改写的扩展关键词，帮助提升检索召回率。
            若查询为 AMBIGUOUS，rewriteHints 为空数组。
            """;

    @Override
    public boolean canHandle(String query) {
        return true;
    }

    /**
     * 使用 LLM 进行意图分类
     *
     * <h3>容错策略</h3>
     * 任何异常（LLM 调用失败、JSON 解析失败、未知意图）都返回 AMBIGUOUS，
     * 确保不阻塞主流程。
     *
     * @param query 用户查询文本
     * @return 意图识别结果，保证非 null
     */
    @Override
    public IntentResult classify(String query) {
        try {
            String userPrompt = "查询：" + query;

            List<ChatMessage> messages = List.of(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(userPrompt)
            );

            ChatResponse llmResponse = chatModel.chat(messages);
            String response = llmResponse.aiMessage().text();

            log.debug("LLM 原始响应: {}", response);
            return parseResponse(response);

        } catch (Exception e) {
            log.warn("LLM 意图分类失败，降级为 AMBIGUOUS: {}", e.getMessage());
            return IntentResult.ambiguous();
        }
    }

    /**
     * 解析 LLM 返回的 JSON 响应
     *
     * <h3>解析策略</h3>
     * <ol>
     *   <li>提取 JSON（去除可能的 Markdown 代码块标记）</li>
     *   <li>解析为 Map</li>
     *   <li>校验并映射意图类型</li>
     *   <li>构建 IntentResult</li>
     * </ol>
     */
    private IntentResult parseResponse(String llmResponse) {
        try {
            String json = extractJson(llmResponse);
            Map<String, Object> map = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            QueryIntent primaryIntent = parseIntent((String) map.get("primaryIntent"));
            double confidence = parseConfidence(map.get("confidence"));
            List<IntentCandidate> alternatives = parseAlternatives(map.get("alternatives"));
            List<String> rewriteHints = parseRewriteHints(map.get("rewriteHints"));

            return IntentResult.of(primaryIntent, confidence, alternatives, rewriteHints);

        } catch (Exception e) {
            log.warn("LLM 响应 JSON 解析失败: {}", e.getMessage());
            return IntentResult.ambiguous();
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 字符串
     * <p>
     * 处理 LLM 可能包裹的 Markdown 代码块（`json ... `）。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("`")) {
            int start = trimmed.indexOf("\n");
            int end = trimmed.lastIndexOf("`");
            if (start > 0 && end > start) {
                return trimmed.substring(start + 1, end).trim();
            }
        }
        return trimmed;
    }

    /**
     * 字符串映射为 QueryIntent 枚举
     */
    private QueryIntent parseIntent(String intentStr) {
        if (intentStr == null) {
            return QueryIntent.AMBIGUOUS;
        }
        try {
            return QueryIntent.valueOf(intentStr.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            log.warn("LLM 返回未知意图类型: {}", intentStr);
            return QueryIntent.AMBIGUOUS;
        }
    }

    /**
     * 安全解析置信度（0.0 ~ 1.0）
     */
    private double parseConfidence(Object confidenceObj) {
        if (confidenceObj instanceof Number num) {
            return Math.max(0.0, Math.min(1.0, num.doubleValue()));
        }
        return 0.5;
    }

    /**
     * 解析备选意图列表
     */
    @SuppressWarnings("unchecked")
    private List<IntentCandidate> parseAlternatives(Object alternativesObj) {
        if (!(alternativesObj instanceof List<?> list)) {
            return Collections.emptyList();
        }
        return list.stream()
                .filter(item -> item instanceof Map)
                .map(item -> (Map<String, Object>) item)
                .map(m -> {
                    QueryIntent intent = parseIntent((String) m.get("intent"));
                    double prob = parseConfidence(m.get("probability"));
                    return new IntentCandidate(intent, prob);
                })
                .collect(Collectors.toList());
    }

    /**
     * 解析改写提示词列表
     */
    @SuppressWarnings("unchecked")
    private List<String> parseRewriteHints(Object hintsObj) {
        if (hintsObj instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
