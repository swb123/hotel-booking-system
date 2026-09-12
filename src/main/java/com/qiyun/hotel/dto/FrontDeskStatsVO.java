package com.qiyun.hotel.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/** 前台今日经营统计 */
@Data
public class FrontDeskStatsVO {

    /** 今日入住过的订单数（含当日入住当日退房，不限当前状态） */
    private int todayCheckIn;

    /** 今日已办理退房数 */
    private int todayCheckOut;

    /** 当前在住数 */
    private int inHouse;

    /** 今日营业额（今日创建、已支付且未取消的订单金额合计，排除待支付） */
    private BigDecimal todayRevenue;

    /** 今日整体入住率（%） */
    private BigDecimal occupancyRate;

    /** 分房型入住率 */
    private List<RoomTypeOccupancyVO> roomTypes;
}
