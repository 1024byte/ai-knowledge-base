package com.hai.aiknowledgebase.common;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code;      // 状态码：0-成功，非0-失败
    private String message;    // 提示信息
    private T data;            // 数据

    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // 成功（无数据）
    public static <T> Result<T> success() {
        return new Result<>(0, "success", null);
    }

    // 成功（有数据）
    public static <T> Result<T> success(T data) {
        return new Result<>(0, "success", data);
    }

    // 失败（自定义消息）
    public static <T> Result<T> error(String message) {
        return new Result<>(-1, message, null);
    }

    // 失败（自定义状态码和消息）
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    // 失败（根据异常构造）
    public static <T> Result<T> error(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }
}