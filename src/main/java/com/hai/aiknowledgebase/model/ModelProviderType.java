package com.hai.aiknowledgebase.model;

/**
 * 模型供应商类型。
 *
 * <ul>
 *   <li>{@link #OPENAI_COMPAT}：OpenAI 兼容协议（DeepSeek / 通义 / 豆包 / Kimi / 智谱等）</li>
 *   <li>{@link #OLLAMA}：本地 Ollama 服务</li>
 *   <li>{@link #ONNX}：本地 ONNX 模型（仅 Rerank 使用）</li>
 *   <li>{@link #DASHSCOPE}：阿里云 DashScope 原生接口（仅 Rerank 使用）</li>
 *   <li>{@link #AUTO}：云端优先、失败回退本地（仅 Rerank 使用）</li>
 * </ul>
 */
public enum ModelProviderType {

    OPENAI_COMPAT,
    OLLAMA,
    ONNX,
    DASHSCOPE,
    AUTO;

    /** 解析数据库中的供应商字符串，非法值返回 null。 */
    public static ModelProviderType of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
