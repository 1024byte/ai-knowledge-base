package com.hai.aiknowledgebase.common;

import lombok.Getter;

@Getter
public enum ResultCode {
    // 通用错误
    SUCCESS(0, "操作成功"),
    FAIL(-1, "操作失败"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_SERVER_ERROR(500, "服务器内部错误"),

    // 业务错误（1000+）
    CATEGORY_EXISTS(1001, "分类已存在"),
    CATEGORY_NOT_EMPTY(1002, "分类下还有文档，请先删除文档"),
    DOCUMENT_NOT_FOUND(1003, "文档不存在"),
    FILE_SAVE_ERROR(1004, "文件保存失败"),
    FILE_READ_ERROR(1008, "文件读取失败"),
    UNSUPPORTED_FILE_TYPE(1005, "不支持的文件类型"),
    VECTOR_DELETE_ERROR(1006, "向量删除失败"),
    FILE_CONTENT_EXIST(1007, "该文档的正文内容已存在，无需重复上传"),

    // 数据库错误（2000+）
    DB_ERROR(2001, "数据库操作异常"),
    DATA_INTEGRITY_VIOLATION(2002, "数据完整性异常"),

    // AI 相关（3000+）
    AI_SERVICE_ERROR(3001, "AI 服务调用失败"),
    EMBEDDING_ERROR(3002, "向量化处理失败"),
    CHAT_ERROR(3003, "对话生成失败");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}