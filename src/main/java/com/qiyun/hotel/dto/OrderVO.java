package com.qiyun.hotel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/** 订单视图：主信息 + 房型 + 状态时间线 */
@Data
public class OrderVO {

    private String orderNo;
    private Long roomTypeId;
    private String roomTypeCode;
    private String roomTypeName;

    private LocalDate checkIn;
    private LocalDate checkOut;
    private long nights;
    private Integer roomCount;

    private String guestName;
    private String guestPhone;
    private String guestIdNo;

    private BigDecimal totalPrice;

    private String status;
    private String statusLabel;
    private LocalDateTime createdAt;

    /** 状态时间线（order_log 流水） */
    private List<OrderLogVO> logs;
}
