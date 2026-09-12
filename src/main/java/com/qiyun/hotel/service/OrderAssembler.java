package com.qiyun.hotel.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.domain.DateRules;
import com.qiyun.hotel.domain.entity.HotelOrder;
import com.qiyun.hotel.domain.entity.OrderLog;
import com.qiyun.hotel.domain.entity.RoomType;
import com.qiyun.hotel.domain.enums.OrderStatus;
import com.qiyun.hotel.dto.OrderLogVO;
import com.qiyun.hotel.dto.OrderVO;
import com.qiyun.hotel.mapper.OrderLogMapper;
import com.qiyun.hotel.mapper.RoomTypeMapper;

/**
 * 订单视图装配：实体 → VO（含房型信息与状态时间线）。
 * 批量装配用批量查询避免 N+1。
 */
public final class OrderAssembler {

    private OrderAssembler() {
    }

    public static OrderVO single(HotelOrder order, RoomTypeMapper roomTypeMapper, OrderLogMapper logMapper) {
        return list(Collections.singletonList(order), roomTypeMapper, logMapper).get(0);
    }

    public static List<OrderVO> list(List<HotelOrder> orders, RoomTypeMapper roomTypeMapper, OrderLogMapper logMapper) {
        if (orders.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> typeIds = orders.stream().map(HotelOrder::getRoomTypeId).distinct().collect(Collectors.toList());
        Map<Long, RoomType> typeMap = roomTypeMapper.selectBatchIds(typeIds).stream()
                .collect(Collectors.toMap(RoomType::getId, t -> t, (a, b) -> a));

        List<Long> orderIds = orders.stream().map(HotelOrder::getId).collect(Collectors.toList());
        Map<Long, List<OrderLog>> logMap = logMapper.selectList(new LambdaQueryWrapper<OrderLog>()
                        .in(OrderLog::getOrderId, orderIds)
                        .orderByAsc(OrderLog::getId))
                .stream()
                .collect(Collectors.groupingBy(OrderLog::getOrderId));

        return orders.stream()
                .map(o -> toVO(o, typeMap.get(o.getRoomTypeId()),
                        logMap.getOrDefault(o.getId(), Collections.emptyList())))
                .collect(Collectors.toList());
    }

    private static OrderVO toVO(HotelOrder order, RoomType roomType, List<OrderLog> logs) {
        OrderVO vo = new OrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setRoomTypeId(order.getRoomTypeId());
        if (roomType != null) {
            vo.setRoomTypeCode(roomType.getCode());
            vo.setRoomTypeName(roomType.getName());
        }
        vo.setCheckIn(order.getCheckInDate());
        vo.setCheckOut(order.getCheckOutDate());
        vo.setNights(DateRules.nights(order.getCheckInDate(), order.getCheckOutDate()));
        vo.setRoomCount(order.getRoomCount());
        vo.setGuestName(order.getGuestName());
        vo.setGuestPhone(order.getGuestPhone());
        vo.setGuestIdNo(order.getGuestIdNo());
        vo.setTotalPrice(order.getTotalPrice());
        vo.setStatus(order.getStatus());
        vo.setStatusLabel(OrderStatus.from(order.getStatus()).label());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setLogs(logs.stream().map(OrderAssembler::toLogVO).collect(Collectors.toList()));
        return vo;
    }

    private static OrderLogVO toLogVO(OrderLog log) {
        OrderLogVO vo = new OrderLogVO();
        vo.setFromStatus(log.getFromStatus());
        vo.setToStatus(log.getToStatus());
        vo.setToStatusLabel(OrderStatus.from(log.getToStatus()).label());
        if (log.getFromStatus() != null) {
            vo.setFromStatusLabel(OrderStatus.from(log.getFromStatus()).label());
        }
        vo.setOperator(log.getOperator());
        vo.setRemark(log.getRemark());
        vo.setCreatedAt(log.getCreatedAt());
        return vo;
    }
}
