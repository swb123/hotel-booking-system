package com.qiyun.hotel.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.common.BizException;
import com.qiyun.hotel.common.ErrorCode;
import com.qiyun.hotel.config.AppProperties;
import com.qiyun.hotel.domain.DateRules;
import com.qiyun.hotel.domain.LosPolicy;
import com.qiyun.hotel.domain.PricingPolicy;
import com.qiyun.hotel.domain.entity.DailyInventory;
import com.qiyun.hotel.domain.entity.DailyPrice;
import com.qiyun.hotel.domain.entity.HotelOrder;
import com.qiyun.hotel.domain.entity.LosRule;
import com.qiyun.hotel.domain.entity.OrderLog;
import com.qiyun.hotel.domain.entity.RoomType;
import com.qiyun.hotel.domain.enums.OrderStatus;
import com.qiyun.hotel.dto.CreateOrderRequest;
import com.qiyun.hotel.dto.OrderVO;
import com.qiyun.hotel.mapper.DailyInventoryMapper;
import com.qiyun.hotel.mapper.DailyPriceMapper;
import com.qiyun.hotel.mapper.HotelOrderMapper;
import com.qiyun.hotel.mapper.LosRuleMapper;
import com.qiyun.hotel.mapper.OrderLogMapper;
import com.qiyun.hotel.mapper.RoomTypeMapper;
import com.qiyun.hotel.util.OrderNoGenerator;

import lombok.RequiredArgsConstructor;

/**
 * 预订下单（核心事务编排）。
 *
 * <p>防超卖流程（同一事务内，任一步失败整体回滚）：
 * <ol>
 *   <li>幂等快速路径：request_id 命中直接返回已存在订单（防双击/重试）；</li>
 *   <li>SELECT ... FOR UPDATE 锁定 [入住日, 离店日) 每日库存行（日期升序防死锁）；</li>
 *   <li>锁内校验剩余房量 ≥ 需求，不足抛 INSUFFICIENT_INVENTORY；</li>
 *   <li>按价格日历计价（DECIMAL），落单 + 状态流水 + 扣减库存。</li>
 * </ol>
 * 并发重复提交撞 request_id 唯一索引时，本事务回滚（含库存扣减），返回已落库的订单 —— 幂等语义。
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final PlatformTransactionManager txManager;
    private final RoomTypeMapper roomTypeMapper;
    private final DailyInventoryMapper inventoryMapper;
    private final DailyPriceMapper priceMapper;
    private final HotelOrderMapper orderMapper;
    private final OrderLogMapper logMapper;
    private final LosRuleMapper losRuleMapper;
    private final OrderNoGenerator orderNoGenerator;
    private final AppProperties props;
    private final Clock clock;

    public OrderVO create(CreateOrderRequest req) {
        HotelOrder existing = findByRequestId(req.getRequestId());
        if (existing != null) {
            return OrderAssembler.single(existing, roomTypeMapper, logMapper);
        }
        try {
            return new TransactionTemplate(txManager).execute(status -> createInTx(req));
        } catch (DuplicateKeyException e) {
            // 情形一：并发重复提交撞 request_id 唯一索引 → 返回"胜者"已创建的订单（幂等语义）
            HotelOrder winner = findByRequestId(req.getRequestId());
            if (winner != null) {
                log.info("幂等命中 requestId={} orderNo={}", req.getRequestId(), winner.getOrderNo());
                return OrderAssembler.single(winner, roomTypeMapper, logMapper);
            }
            // 情形二：订单号撞唯一索引（生成器 check-then-insert 的非原子竞态）→ 换新订单号重试一次
            log.warn("订单号冲突（非幂等冲突），重试一次 requestId={}", req.getRequestId());
            return new TransactionTemplate(txManager).execute(status -> createInTx(req));
        }
    }

    private HotelOrder findByRequestId(String requestId) {
        return orderMapper.selectOne(new LambdaQueryWrapper<HotelOrder>()
                .eq(HotelOrder::getRequestId, requestId));
    }

    private OrderVO createInTx(CreateOrderRequest req) {
        // 1) 日期与参数规则
        LocalDate today = LocalDate.now(clock);
        DateRules.validate(req.getCheckIn(), req.getCheckOut(), today,
                props.getBooking().getMaxStayNights(), props.getBooking().getMaxAdvanceDays());
        int roomCount = req.getRoomCount();
        if (roomCount > props.getBooking().getMaxRoomsPerOrder()) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "单笔订单最多预订 " + props.getBooking().getMaxRoomsPerOrder() + " 间");
        }

        // 2) 房型校验
        RoomType roomType = roomTypeMapper.selectById(req.getRoomTypeId());
        if (roomType == null || !Boolean.TRUE.equals(roomType.getActive())) {
            throw new BizException(ErrorCode.ROOM_TYPE_NOT_FOUND, "房型不存在或已下架");
        }
        List<LocalDate> dates = DateRules.nightDates(req.getCheckIn(), req.getCheckOut());

        // 2.5) LOS 收益管理校验（MinLOS/MaxLOS）：与入住区间重叠的规则生效，违反即拒单（锁库存之前快速失败）
        List<LosRule> losRules = losRuleMapper.selectList(new LambdaQueryWrapper<LosRule>()
                .eq(LosRule::getRoomTypeId, roomType.getId())
                .le(LosRule::getStartDate, req.getCheckOut().minusDays(1))
                .ge(LosRule::getEndDate, req.getCheckIn()));
        LosPolicy.validate(losRules, req.getCheckIn(), req.getCheckOut());

        // 3) 行锁锁定每日库存（防超卖核心：锁内校验-扣减）
        List<DailyInventory> locked = inventoryMapper.lockByTypeAndDates(roomType.getId(), dates);
        if (locked.size() != dates.size()) {
            throw new BizException(ErrorCode.INVENTORY_NOT_READY, "部分日期的房量库存尚未初始化，请联系管理员");
        }

        // 4) 锁内二次幂等检查：并发同 requestId 重放时，等行锁的后来者在拿到锁后
        //    能看到已提交的订单并直接返回，而不是先撞"房量不足"破坏幂等契约
        HotelOrder existing = findByRequestId(req.getRequestId());
        if (existing != null) {
            return OrderAssembler.single(existing, roomTypeMapper, logMapper);
        }
        for (DailyInventory inv : locked) {
            int remaining = inv.getTotalRooms() - inv.getSoldRooms();
            if (remaining < roomCount) {
                throw new BizException(ErrorCode.INSUFFICIENT_INVENTORY,
                        String.format("%s 房量不足：剩余 %d 间，需要 %d 间", inv.getBizDate(), remaining, roomCount));
            }
        }

        // 5) 价格日历计价（缺失日期回退基准价，见 PricingPolicy）
        Map<LocalDate, BigDecimal> calendar = priceMapper.selectList(new LambdaQueryWrapper<DailyPrice>()
                        .eq(DailyPrice::getRoomTypeId, roomType.getId())
                        .in(DailyPrice::getBizDate, dates))
                .stream()
                .collect(Collectors.toMap(DailyPrice::getBizDate, DailyPrice::getPrice, (a, b) -> a));
        BigDecimal total = PricingPolicy.total(roomType, dates, calendar, roomCount);

        // 6) 落单 + 状态流水 + 扣减库存
        LocalDateTime now = LocalDateTime.now(clock);
        HotelOrder order = new HotelOrder();
        order.setOrderNo(orderNoGenerator.next());
        order.setRoomTypeId(roomType.getId());
        order.setCheckInDate(req.getCheckIn());
        order.setCheckOutDate(req.getCheckOut());
        order.setRoomCount(roomCount);
        order.setGuestName(req.getGuestName());
        order.setGuestPhone(req.getGuestPhone());
        order.setGuestIdNo(req.getGuestIdNo());
        order.setTotalPrice(total);
        order.setStatus(OrderStatus.PENDING_PAYMENT.name());
        order.setRequestId(req.getRequestId());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderMapper.insert(order);

        writeLog(order.getId(), null, OrderStatus.PENDING_PAYMENT, "guest", "提交预订，已锁定房量", now);

        for (DailyInventory inv : locked) {
            inv.setSoldRooms(inv.getSoldRooms() + roomCount);
            inventoryMapper.updateById(inv);
        }

        log.info("订单创建成功 orderNo={} 房型={} 日期={}~{} 间数={} 金额={}",
                order.getOrderNo(), roomType.getCode(), req.getCheckIn(), req.getCheckOut(), roomCount, total);
        return OrderAssembler.single(order, roomTypeMapper, logMapper);
    }

    private void writeLog(Long orderId, OrderStatus from, OrderStatus to, String operator, String remark,
                          LocalDateTime time) {
        OrderLog entry = new OrderLog();
        entry.setOrderId(orderId);
        entry.setFromStatus(from == null ? null : from.name());
        entry.setToStatus(to.name());
        entry.setOperator(operator);
        entry.setRemark(remark);
        entry.setCreatedAt(time);
        logMapper.insert(entry);
    }
}
