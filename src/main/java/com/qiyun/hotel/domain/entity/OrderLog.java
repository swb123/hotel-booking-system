package com.qiyun.hotel.domain.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 订单状态流水：每次状态迁移追加一条（轻量事件溯源），
 * 支撑订单时间线展示、审计与对账。operator: guest / frontdesk / system。
 */
@Data
@TableName("order_log")
public class OrderLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** 迁移前状态，首条记录（创建）为 null */
    private String fromStatus;

    private String toStatus;

    private String operator;

    private String remark;

    private LocalDateTime createdAt;
}
