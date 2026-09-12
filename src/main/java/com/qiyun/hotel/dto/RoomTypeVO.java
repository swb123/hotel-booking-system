package com.qiyun.hotel.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class RoomTypeVO {

    private Long id;
    private String code;
    private String name;
    private BigDecimal basePrice;
    private Integer totalRooms;
    private String amenities;
}
