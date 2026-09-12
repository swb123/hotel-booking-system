package com.qiyun.hotel.common;

/**
 * 业务错误码：code 面向前端/调用方，httpStatus 为对应的 HTTP 语义。
 */
public enum ErrorCode {

    INVALID_PARAM(400),
    INVALID_DATE_RANGE(400),
    EARLY_CHECK_IN(400),
    CHECK_IN_EXPIRED(409),
    LOS_MIN_NOT_MET(400),
    LOS_MAX_EXCEEDED(400),

    ROOM_TYPE_NOT_FOUND(404),
    ORDER_NOT_FOUND(404),

    INSUFFICIENT_INVENTORY(409),
    INVALID_STATE_TRANSITION(409),
    INVENTORY_NOT_READY(409),

    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
