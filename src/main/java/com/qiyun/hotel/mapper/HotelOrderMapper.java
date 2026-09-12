package com.qiyun.hotel.mapper;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiyun.hotel.domain.entity.HotelOrder;

public interface HotelOrderMapper extends BaseMapper<HotelOrder> {

    /**
     * 行锁读订单：状态流转（支付/取消/入住/退房）前锁定订单行，
     * 防止并发重复操作（如前台双人同时为同一订单办理入住）。
     */
    HotelOrder selectForUpdateByOrderNo(@Param("orderNo") String orderNo);
}
