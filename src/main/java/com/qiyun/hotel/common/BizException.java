package com.qiyun.hotel.common;

/**
 * 业务异常：领域规则被违反时抛出（房量不足、非法状态迁移、提前入住等），
 * 由 {@link GlobalExceptionHandler} 统一转成结构化响应。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return errorCode.getHttpStatus();
    }
}
