package com.qiyun.hotel.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.domain.entity.DailyInventory;
import com.qiyun.hotel.domain.entity.HotelOrder;
import com.qiyun.hotel.domain.entity.RoomType;
import com.qiyun.hotel.domain.enums.OrderStatus;
import com.qiyun.hotel.dto.FrontDeskStatsVO;
import com.qiyun.hotel.dto.OrderVO;
import com.qiyun.hotel.dto.RoomTypeOccupancyVO;
import com.qiyun.hotel.mapper.DailyInventoryMapper;
import com.qiyun.hotel.mapper.HotelOrderMapper;
import com.qiyun.hotel.mapper.OrderLogMapper;
import com.qiyun.hotel.mapper.RoomTypeMapper;

import lombok.RequiredArgsConstructor;

/**
 * 前台管理：订单列表（状态/日期筛选）、今日经营统计（入住/退房/在住/营收/入住率）。
 */
@Service
@RequiredArgsConstructor
public class FrontDeskService {

    private final HotelOrderMapper orderMapper;
    private final DailyInventoryMapper inventoryMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final OrderLogMapper logMapper;
    private final Clock clock;

    public List<OrderVO> orders(String status, LocalDate date) {
        List<HotelOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<HotelOrder>()
                .eq(status != null && !status.isEmpty(), HotelOrder::getStatus, status)
                .eq(date != null, HotelOrder::getCheckInDate, date)
                .orderByDesc(HotelOrder::getId)
                .last("LIMIT 200"));
        return OrderAssembler.list(orders, roomTypeMapper, logMapper);
    }

    public FrontDeskStatsVO stats() {
        LocalDate today = LocalDate.now(clock);

        // 今日入住口径：今日入住过的订单（含当日入住当日退房的），不限当前状态
        long todayCheckIn = orderMapper.selectCount(new LambdaQueryWrapper<HotelOrder>()
                .eq(HotelOrder::getCheckInDate, today)
                .in(HotelOrder::getStatus, OrderStatus.CHECKED_IN.name(), OrderStatus.CHECKED_OUT.name()));
        long todayCheckOut = orderMapper.selectCount(new LambdaQueryWrapper<HotelOrder>()
                .eq(HotelOrder::getCheckOutDate, today)
                .eq(HotelOrder::getStatus, OrderStatus.CHECKED_OUT.name()));
        long inHouse = orderMapper.selectCount(new LambdaQueryWrapper<HotelOrder>()
                .eq(HotelOrder::getStatus, OrderStatus.CHECKED_IN.name()));

        // 今日营业额：今日创建、已支付（排除待支付）且未取消的订单金额合计（演示口径，生产以支付成功流水为准）
        List<HotelOrder> todayOrders = orderMapper.selectList(new LambdaQueryWrapper<HotelOrder>()
                .ge(HotelOrder::getCreatedAt, today.atStartOfDay())
                .notIn(HotelOrder::getStatus, OrderStatus.PENDING_PAYMENT.name(), OrderStatus.CANCELLED.name()));
        BigDecimal revenue = todayOrders.stream()
                .map(HotelOrder::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 今日入住率：按房型汇总库存 sold/total
        List<DailyInventory> todayInvs = inventoryMapper.selectList(new LambdaQueryWrapper<DailyInventory>()
                .eq(DailyInventory::getBizDate, today));
        Map<Long, RoomType> typeMap = roomTypeMapper.selectList(new LambdaQueryWrapper<RoomType>()
                        .eq(RoomType::getActive, true)).stream()
                .collect(Collectors.toMap(RoomType::getId, t -> t, (a, b) -> a));

        Map<Long, int[]> agg = new HashMap<>();
        for (DailyInventory inv : todayInvs) {
            int[] a = agg.computeIfAbsent(inv.getRoomTypeId(), k -> new int[2]);
            a[0] += inv.getTotalRooms();
            a[1] += inv.getSoldRooms();
        }

        int totalAll = 0;
        int soldAll = 0;
        List<RoomTypeOccupancyVO> rows = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : agg.entrySet()) {
            RoomType rt = typeMap.get(e.getKey());
            if (rt == null) {
                continue;
            }
            RoomTypeOccupancyVO row = new RoomTypeOccupancyVO();
            row.setCode(rt.getCode());
            row.setName(rt.getName());
            row.setTotal(e.getValue()[0]);
            row.setSold(e.getValue()[1]);
            row.setRate(percent(e.getValue()[1], e.getValue()[0]));
            rows.add(row);
            totalAll += e.getValue()[0];
            soldAll += e.getValue()[1];
        }

        FrontDeskStatsVO vo = new FrontDeskStatsVO();
        vo.setTodayCheckIn(Math.toIntExact(todayCheckIn));
        vo.setTodayCheckOut(Math.toIntExact(todayCheckOut));
        vo.setInHouse(Math.toIntExact(inHouse));
        vo.setTodayRevenue(revenue);
        vo.setOccupancyRate(percent(soldAll, totalAll));
        vo.setRoomTypes(rows);
        return vo;
    }

    private static BigDecimal percent(int sold, int total) {
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(sold)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }
}
