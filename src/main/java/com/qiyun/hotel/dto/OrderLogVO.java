package com.qiyun.hotel.dto;

import java.time.LocalDateTime;

import lombok.Data;

/** 订单状态流水（时间线节点） */
@Data
public class OrderLogVO {

    private String fromStatus;
    private String fromStatusLabel;
    private String toStatus;
    private String toStatusLabel;

    /** guest=客人 frontdesk=前台 system=系统 */
    private String operator;

    private String remark;
    private LocalDateTime createdAt;
}
