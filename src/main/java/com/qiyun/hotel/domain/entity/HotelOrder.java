package com.qiyun.hotel.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 订单主表（order 为 SQL 保留字，故表名 hotel_order）。
 * - request_id 唯一索引：幂等键，同一请求重复提交返回同一订单；
 * - status 存状态枚举名，流转受 {@link com.qiyun.hotel.domain.enums.OrderStatus} 状态机约束。
 */
@Data
@TableName("hotel_order")
public class HotelOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long roomTypeId;

    private LocalDate checkInDate;

    private LocalDate checkOutDate;

    private Integer roomCount;

    private String guestName;

    private String guestPhone;

    private String guestIdNo;

    /** 总价（元），DECIMAL */
    private BigDecimal totalPrice;

    /** 订单状态（OrderStatus.name()） */
    private String status;

    /** 幂等键（客户端每次下单动作生成一次，如 crypto.randomUUID()） */
    private String requestId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
