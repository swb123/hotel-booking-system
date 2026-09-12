package com.qiyun.hotel.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/** 房态：区间内某房型的可售余量与每晚价格 */
@Data
public class AvailabilityVO {

    private Long roomTypeId;
    private String code;
    private String name;
    private String amenities;
    private BigDecimal basePrice;

    /** 间夜数 */
    private int nights;

    /** LOS 收益管理：区间内生效的最小连住晚数（0=无限制），如"国庆档最少连住 3 晚" */
    private int minLosNights;

    /** 可售余量（区间内各日剩余的最小值，每天都有房才可订） */
    private int remaining;

    /** 每晚价格明细 */
    private List<NightPriceVO> priceDetails;

    /** 区间总价（1 间） */
    private BigDecimal totalPrice;
}
