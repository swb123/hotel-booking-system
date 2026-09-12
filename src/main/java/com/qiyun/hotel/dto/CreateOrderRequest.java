package com.qiyun.hotel.dto;

import java.time.LocalDate;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import lombok.Data;

/**
 * 创建预订请求。
 * requestId 为幂等键：前端每次"下单动作"生成一次（如 crypto.randomUUID()），
 * 双击/重试携带同一 requestId 只会产生一笔订单。
 */
@Data
public class CreateOrderRequest {

    @NotNull(message = "房型不能为空")
    private Long roomTypeId;

    @NotNull(message = "入住日期不能为空")
    private LocalDate checkIn;

    @NotNull(message = "离店日期不能为空")
    private LocalDate checkOut;

    @NotNull(message = "房间数不能为空")
    @Min(value = 1, message = "房间数至少为 1")
    // 上限由服务层按配置 app.booking.max-rooms-per-order 校验，避免配置与注解双源不一致
    private Integer roomCount;

    @NotBlank(message = "入住人姓名不能为空")
    @Size(max = 64, message = "姓名长度超限")
    private String guestName;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String guestPhone;

    @Size(max = 32, message = "证件号长度超限")
    private String guestIdNo;

    @NotBlank(message = "请求幂等键不能为空")
    @Size(min = 8, max = 64, message = "幂等键长度需在 8~64 之间")
    private String requestId;
}
