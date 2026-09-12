package com.qiyun.hotel.domain.entity;

import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 每日库存：防超卖的并发控制单元。
 * 唯一约束 (room_type_id, biz_date)；下单事务以 SELECT ... FOR UPDATE 锁定这些行后校验并扣减。
 */
@Data
@TableName("daily_inventory")
public class DailyInventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roomTypeId;

    /** 业务日期（入住夜） */
    private LocalDate bizDate;

    /** 该日可售总房量 */
    private Integer totalRooms;

    /** 该日已售房量（含占房中的订单） */
    private Integer soldRooms;
}
