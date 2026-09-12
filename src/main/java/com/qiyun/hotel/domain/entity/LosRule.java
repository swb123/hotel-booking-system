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
