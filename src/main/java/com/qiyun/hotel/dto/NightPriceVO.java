package com.qiyun.hotel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/** 单晚价格明细（价格日历，支持周末/节假日差异化） */
@Data
public class NightPriceVO {

    private LocalDate date;
    private BigDecimal price;
}
