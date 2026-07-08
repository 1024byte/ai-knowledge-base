package com.hai.aiknowledgebase.exception;

import com.hai.aiknowledgebase.common.ResultCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final Integer code;
    private final String message;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
    }

    public BusinessException(ResultCode resultCode, String extraMessage) {
        super(resultCode.getMessage() + ": " + extraMessage);
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage() + ": " + extraMessage;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.FAIL.getCode();
        this.message = message;
    }
}