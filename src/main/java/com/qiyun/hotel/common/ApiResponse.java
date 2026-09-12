package com.qiyun.hotel.common;

import lombok.Data;

/**
 * 统一响应体：{ code, message, data }。
 * 成功 code = SUCCESS；失败 code = 业务错误码（见 {@link ErrorCode}）。
 */
@Data
public class ApiResponse<T> {

    public static final String SUCCESS = "SUCCESS";

    private String code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(SUCCESS);
        resp.setMessage("OK");
        resp.setData(data);
        return resp;
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(code);
        resp.setMessage(message);
        return resp;
    }
}
