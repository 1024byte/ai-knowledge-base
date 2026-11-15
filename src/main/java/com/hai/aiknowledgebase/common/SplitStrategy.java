package com.hai.aiknowledgebase.common;

import lombok.Getter;

/**
 * <h2>切片策略枚举</h2>
 *
 * <p>知识库级切片策略，存储在 {@code knowledge_base_config.split_strategy}。
 * 各策略含义：</p>
 * <table>
 *   <tr><th>枚举</th><th>code</th><th>说明</th></tr>
 *   <tr><td>AUTO</td><td>auto</td><td>系统按文档内容自动识别并推荐（走内容路由）</td></tr>
 *   <tr><td>RECURSIVE</td><td>recursive</td><td>递归字符分割（通用兜底）</td></tr>
 *   <tr><td>SENTENCE</td><td>sentence</td><td>按句子分割（短句密集文本）</td></tr>
 *   <tr><td>PARAGRAPH</td><td>paragraph</td><td>按段落分割（无标题纯文本长文）</td></tr>
 *   <tr><td>LINE</td><td>line</td><td>按行分割（代码、日志、对话记录）</td></tr>
 * </table>
 */
@Getter
public enum SplitStrategy {

    AUTO("auto", "自动（系统识别文档内容推荐）"),
    RECURSIVE("recursive", "递归字符分割"),
    SENTENCE("sentence", "按句子分割"),
    PARAGRAPH("paragraph", "按段落分割"),
    LINE("line", "按行分割");

    private final String code;
    private final String desc;

    SplitStrategy(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 从存储值解析切片策略，未知值回退 {@link #AUTO}。
     *
     * @param value 存储值（枚举名或 code，忽略大小写）
     * @return 对应枚举，未知值返回 AUTO
     */
    public static SplitStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        for (SplitStrategy strategy : values()) {
            if (strategy.name().equalsIgnoreCase(value) || strategy.code.equalsIgnoreCase(value)) {
                return strategy;
            }
        }
        return AUTO;
    }

    /**
     * 校验存储值是否为合法切片策略（用于创建/更新知识库时的入参校验）。
     *
     * @param value 待校验值
     * @return 是否合法
     */
    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (SplitStrategy strategy : values()) {
            if (strategy.name().equalsIgnoreCase(value) || strategy.code.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}