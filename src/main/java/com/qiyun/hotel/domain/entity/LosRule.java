package com.qiyun.hotel.domain.entity;

import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * LOS（Length Of Stay）收益管理规则：按"房型 × 日期段"约束连住晚数。
 * 例：国庆档大床房 09-19 ~ 09-21 最少连住 2 晚（MinLOS=2）、最长 5 晚（MaxLOS=5）。
 * 语义：入住区间 [checkIn, checkOut) 与 [startDate, endDate] 有重叠时，规则生效。
 *
 * <p>领域定位（诚实标注）：行业标准中 LOS 约束挂在<b>房价码（RatePlan）</b>维度，
 * 与价格同属收益管理对象（同一房型可挂多个房价码，各自有价格日历与 LOS/CTA/CTD 限制）。
 * 本系统为<b>单房价码（BAR-only）简化模型</b>——全店一个价格计划，规则直接挂房型。
 * 多房价码演进：新增 rate_plan 表（id, room_type_id, name…），daily_price 与 los_rule
 * 均挂 rate_plan_id，预订先选房价码再取其价格与约束。
 */
@Data
@TableName("los_rule")
public class LosRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roomTypeId;

    /** 规则生效起始日（含） */
    private LocalDate startDate;

    /** 规则生效截止日（含） */
    private LocalDate endDate;

    /** 最少连住晚数 */
    private Integer minNights;

    /** 最多连住晚数 */
    private Integer maxNights;

    private String remark;
}
