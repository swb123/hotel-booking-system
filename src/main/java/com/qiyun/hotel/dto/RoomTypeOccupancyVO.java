package com.qiyun.hotel.dto;

import java.math.BigDecimal;

import lombok.Data;

/** 房型入住率（今日） */
@Data
public class RoomTypeOccupancyVO {

    private String code;
    private String name;
    private int total;
    private int sold;

    /** 入住率（%），保留 1 位小数 */
    private BigDecimal rate;
}
