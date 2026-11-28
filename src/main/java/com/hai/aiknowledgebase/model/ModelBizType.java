package com.hai.aiknowledgebase.model;

/**
 * 模型业务类型。
 *
 * <p>每个类型在 {@code model_config} 表中最多一条生效配置；
 * 评测运行时通过伪参数 {@code model.chat.id} / {@code model.rewrite.id} / {@code model.rerank.id}
 * 参与参数快照冻结。</p>
 */
public enum ModelBizType {

    CHAT,
    REWRITE,
    RERANK;

    /** 返回大写的类型名（用于数据库存储）。 */
    public String code() {
        return name();
    }

    /** 评测冻结使用的伪参数键，如 {@code model.chat.id}。 */
    public String overrideKey() {
        return "model." + name().toLowerCase() + ".id";
    }

    /** 解析数据库中的类型字符串，非法值返回 null。 */
    public static ModelBizType of(String value) {
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
