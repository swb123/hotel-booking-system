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
 *
 * <p>领域定位（诚实标注）：严格模型中"商品（Product/Sellable Unit）= 房型 × 房价码（RatePlan）"——
 * 房型是物理资源概念（库存归属它），价格/上下架等销售属性属于房价码/商品层。
 * 本系统为单房价码（BAR-only）简化，房型与商品一对一退化，故本实体同时承载
 * 物理属性（total_rooms）与销售属性（base_price/active）。
 * 多房价码演进：新增 product 表（room_type_id + rate_plan_id），销售属性迁移至
 * product/rate_plan，房型回归纯物理资源定义。
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
