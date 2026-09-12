package com.qiyun.hotel.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 每日价格日历：支持周末/节假日差异化定价（如周末上浮 20%）。
 * 计价规则见 {@link com.qiyun.hotel.domain.PricingPolicy}。
 */
@Data
@TableName("daily_price")
public class DailyPrice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roomTypeId;

    private LocalDate bizDate;

    /** 当日价格（元/间夜） */
    private BigDecimal price;
}
