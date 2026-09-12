package com.qiyun.hotel.domain.entity;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 房型：物理房型定义（如大床房/双床房/套房）。
 * 注意：库存不放在房型上，而是按"房型 × 日期"拆分到 daily_inventory ——
 * 这是酒店/机票类系统防超卖的关键建模：并发控制单元是"某房型某晚"的行。
 */
@Data
@TableName("room_type")
public class RoomType {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 房型编码，如 DB / TW / SU */
    private String code;

    /** 房型名称 */
    private String name;

    /** 基准价（元/间夜），DECIMAL 存储 */
    private BigDecimal basePrice;

    /** 该房型物理房间总数 */
    private Integer totalRooms;

    /** 设施描述 */
    private String amenities;

    /** 是否上架 */
    private Boolean active;
}
