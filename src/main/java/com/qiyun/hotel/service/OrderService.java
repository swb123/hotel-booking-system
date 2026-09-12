package com.qiyun.hotel.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.common.BizException;
import com.qiyun.hotel.common.ErrorCode;
import com.qiyun.hotel.domain.DateRules;
import com.qiyun.hotel.domain.entity.DailyInventory;
import com.qiyun.hotel.domain.entity.HotelOrder;
import com.qiyun.hotel.domain.entity.OrderLog;
import com.qiyun.hotel.domain.enums.OrderStatus;
import com.qiyun.hotel.dto.OrderVO;
import com.qiyun.hotel.mapper.DailyInventoryMapper;
import com.qiyun.hotel.mapper.HotelOrderMapper;
import com.qiyun.hotel.mapper.OrderLogMapper;
import com.qiyun.hotel.mapper.RoomTypeMapper;

import lombok.RequiredArgsConstructor;

/**
 * 订单状态流转：支付 / 取消 / 入住 / 退房。
 *
 * <p>统一走 {@link #transition}：行锁读订单 → 守卫校验 → 状态机校验 → 更新 + 流水；
 * 非法迁移（如已入住后取消）返回 409。取消时若原状态占房，则在同事务内归还库存。
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final PlatformTransactionManager txManager;
    private final HotelOrderMapper orderMapper;
    private final DailyInventoryMapper inventoryMapper;
    private final OrderLogMapper logMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final Clock clock;

    /** 模拟支付：待支付 → 已确认（真实场景接支付网关回调） */
    public OrderVO pay(String orderNo) {
        return transition(orderNo, OrderStatus.CONFIRMED, "guest", "模拟支付成功", null, null);
    }

    /** 取消订单：占房状态取消需归还库存 */
    public OrderVO cancel(String orderNo) {
        return transition(orderNo, OrderStatus.CANCELLED, "guest", "用户取消订单", null, null);
    }

    /** 办理入住：守卫规则——不可提前入住（早于入住日）、不可过期入住（晚于离店日） */
    public OrderVO checkIn(String orderNo) {
        return transition(orderNo, OrderStatus.CHECKED_IN, "frontdesk", "办理入住", order -> {
            LocalDate today = LocalDate.now(clock);
            if (today.isBefore(order.getCheckInDate())) {
                throw new BizException(ErrorCode.EARLY_CHECK_IN,
                        "未到入住日期（入住日 " + order.getCheckInDate() + "），不能提前办理入住");
            }
            if (today.isAfter(order.getCheckOutDate())) {
                throw new BizException(ErrorCode.CHECK_IN_EXPIRED,
                        "已超过离店日期（" + order.getCheckOutDate() + "），订单不可再办理入住");
            }
        }, null);
    }

    /**
     * 办理退房：提前退房时释放退房日之后的未住间夜库存
     * （退房当日及之前的间夜视为已消费，不再归还）。
     */
    public OrderVO checkOut(String orderNo) {
        return transition(orderNo, OrderStatus.CHECKED_OUT, "frontdesk", "办理退房", null, order -> {
            LocalDate today = LocalDate.now(clock);
            List<LocalDate> unStayed = DateRules.nightDates(order.getCheckInDate(), order.getCheckOutDate())
                    .stream().filter(d -> d.isAfter(today)).collect(java.util.stream.Collectors.toList());
            if (unStayed.isEmpty()) {
                return;
            }
            List<DailyInventory> locked = inventoryMapper.lockByTypeAndDates(order.getRoomTypeId(), unStayed);
            if (locked.size() != unStayed.size()) {
                throw new BizException(ErrorCode.INVENTORY_NOT_READY,
                        "部分日期的房量库存缺失，无法释放提前退房的间夜，请联系管理员");
            }
            for (DailyInventory inv : locked) {
                inv.setSoldRooms(Math.max(0, inv.getSoldRooms() - order.getRoomCount()));
                inventoryMapper.updateById(inv);
            }
            log.info("提前退房释放库存 orderNo={} 释放间夜数={}", orderNo, unStayed.size());
        });
    }

    private OrderVO transition(String orderNo, OrderStatus target, String operator, String remark,
                               Consumer<HotelOrder> guard, Consumer<HotelOrder> postAction) {
        return new TransactionTemplate(txManager).execute(status -> {
            // 行锁读订单：并发下同一订单同一时刻仅一个操作能进入
            HotelOrder order = orderMapper.selectForUpdateByOrderNo(orderNo);
            if (order == null) {
                throw new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + orderNo);
            }
            OrderStatus from = OrderStatus.from(order.getStatus());

            if (guard != null) {
                guard.accept(order);
            }
            if (!from.canTransitionTo(target)) {
                throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                        String.format("订单当前状态为「%s」，不允许执行该操作", from.label()));
            }

            order.setStatus(target.name());
            order.setUpdatedAt(LocalDateTime.now(clock));
            orderMapper.updateById(order);
            writeLog(order.getId(), from, target, operator, remark);

            // 动作级后置逻辑（如提前退房释放剩余间夜），与状态流转同事务
            if (postAction != null) {
                postAction.accept(order);
            }

            // 取消释放库存：仅从占房状态取消时归还房量；与下单扣减对称地校验库存完整性
            if (target == OrderStatus.CANCELLED && from.releasesInventoryOnCancel()) {
                List<LocalDate> dates = DateRules.nightDates(order.getCheckInDate(), order.getCheckOutDate());
                List<DailyInventory> locked = inventoryMapper.lockByTypeAndDates(order.getRoomTypeId(), dates);
                if (locked.size() != dates.size()) {
                    throw new BizException(ErrorCode.INVENTORY_NOT_READY,
                            "部分日期的房量库存缺失，无法释放库存，请联系管理员");
                }
                for (DailyInventory inv : locked) {
                    inv.setSoldRooms(Math.max(0, inv.getSoldRooms() - order.getRoomCount()));
                    inventoryMapper.updateById(inv);
                }
            }

            log.info("订单状态流转 orderNo={} {} -> {} operator={}", orderNo, from.name(), target.name(), operator);
            return OrderAssembler.single(order, roomTypeMapper, logMapper);
        });
    }

    /** 订单详情（含状态时间线），按订单号 */
    public OrderVO detail(String orderNo) {
        HotelOrder order = orderMapper.selectOne(new LambdaQueryWrapper<HotelOrder>()
                .eq(HotelOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + orderNo);
        }
        return OrderAssembler.single(order, roomTypeMapper, logMapper);
    }

    /** 按入住人手机号查订单列表（近 50 条） */
    public List<OrderVO> listByPhone(String phone) {
        List<HotelOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<HotelOrder>()
                .eq(HotelOrder::getGuestPhone, phone)
                .orderByDesc(HotelOrder::getId)
                .last("LIMIT 50"));
        return OrderAssembler.list(orders, roomTypeMapper, logMapper);
    }

    private void writeLog(Long orderId, OrderStatus from, OrderStatus to, String operator, String remark) {
        OrderLog entry = new OrderLog();
        entry.setOrderId(orderId);
        entry.setFromStatus(from == null ? null : from.name());
        entry.setToStatus(to.name());
        entry.setOperator(operator);
        entry.setRemark(remark);
        entry.setCreatedAt(LocalDateTime.now(clock));
        logMapper.insert(entry);
    }
}
