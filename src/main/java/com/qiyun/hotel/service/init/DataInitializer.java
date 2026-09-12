package com.qiyun.hotel.service.init;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.config.AppProperties;
import com.qiyun.hotel.domain.DateRules;
import com.qiyun.hotel.domain.PricingPolicy;
import com.qiyun.hotel.domain.entity.DailyInventory;
import com.qiyun.hotel.domain.entity.DailyPrice;
import com.qiyun.hotel.domain.entity.HotelOrder;
import com.qiyun.hotel.domain.entity.LosRule;
import com.qiyun.hotel.domain.entity.OrderLog;
import com.qiyun.hotel.domain.entity.RoomType;
import com.qiyun.hotel.domain.enums.OrderStatus;
import com.qiyun.hotel.mapper.DailyInventoryMapper;
import com.qiyun.hotel.mapper.DailyPriceMapper;
import com.qiyun.hotel.mapper.HotelOrderMapper;
import com.qiyun.hotel.mapper.LosRuleMapper;
import com.qiyun.hotel.mapper.OrderLogMapper;
import com.qiyun.hotel.mapper.RoomTypeMapper;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * 幂等初始化数据（演示用；生产对应库存预生成任务与运营配置）：
 * 1. 房型：DB 大床房 / TW 标准双床房 / SU 豪华套房；
 * 2. 自动补足未来 N 天房量库存与价格日历（周五/周六晚价格上浮 20%，演示差异化定价）；
 * 3. 四笔覆盖全状态的演示订单，便于现场直接演示"办理入住/退房/查询"。
 * 全部幂等：数据已存在则跳过，可安全重复启动。
 */
@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final BigDecimal WEEKEND_PREMIUM = new BigDecimal("1.2");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final RoomTypeMapper roomTypeMapper;
    private final DailyInventoryMapper inventoryMapper;
    private final DailyPriceMapper priceMapper;
    private final LosRuleMapper losRuleMapper;
    private final HotelOrderMapper orderMapper;
    private final OrderLogMapper logMapper;
    private final AppProperties props;
    private final Clock clock;
    private final PlatformTransactionManager txManager;

    @Override
    public void run(ApplicationArguments args) {
        LocalDate today = LocalDate.now(clock);
        // 整体事务：任一步失败整体回滚，避免"半初始化"状态被幂等检查误判为已完成
        new TransactionTemplate(txManager).execute(status -> {
            ensureRoomTypes();
            // 从昨天开始生成：演示订单含昨日入住今日退房的历史单，其占房日期也要有库存行
            ensureInventoryAndPrices(today.minusDays(1), today.plusDays(props.getSeed().getInventoryDays() - 1L));
            ensureLosRules(today);
            if (props.getSeed().isDemoOrders()) {
                ensureDemoOrders(today);
            }
            return null;
        });
    }

    private void ensureRoomTypes() {
        if (roomTypeMapper.selectCount(null) > 0) {
            return;
        }
        insertType("DB", "大床房", "368.00", 8, "1.8米大床 · 含双早 · 免费WiFi · 45㎡");
        insertType("TW", "标准双床房", "328.00", 10, "1.2米双床 · 含双早 · 免费WiFi · 32㎡");
        insertType("SU", "豪华套房", "688.00", 3, "独立客厅 · 含双早 · 浴缸 · 迷你吧 · 80㎡");
        log.info("房型初始化完成（DB/TW/SU）");
    }

    private void insertType(String code, String name, String basePrice, int totalRooms, String amenities) {
        RoomType t = new RoomType();
        t.setCode(code);
        t.setName(name);
        t.setBasePrice(new BigDecimal(basePrice));
        t.setTotalRooms(totalRooms);
        t.setAmenities(amenities);
        t.setActive(true);
        roomTypeMapper.insert(t);
    }

    /** 自动补足未来 [start, end] 的库存与价格，已存在的日期跳过 */
    private void ensureInventoryAndPrices(LocalDate start, LocalDate end) {
        for (RoomType rt : roomTypeMapper.selectList(null)) {
            Set<LocalDate> existing = inventoryMapper.selectList(new LambdaQueryWrapper<DailyInventory>()
                            .eq(DailyInventory::getRoomTypeId, rt.getId())
                            .ge(DailyInventory::getBizDate, start)
                            .le(DailyInventory::getBizDate, end))
                    .stream().map(DailyInventory::getBizDate).collect(Collectors.toSet());
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                if (existing.contains(d)) {
                    continue;
                }
                DailyInventory inv = new DailyInventory();
                inv.setRoomTypeId(rt.getId());
                inv.setBizDate(d);
                inv.setTotalRooms(rt.getTotalRooms());
                inv.setSoldRooms(0);
                inventoryMapper.insert(inv);

                DailyPrice price = new DailyPrice();
                price.setRoomTypeId(rt.getId());
                price.setBizDate(d);
                price.setPrice(weekendPrice(rt, d));
                priceMapper.insert(price);
            }
        }
    }

    /** 幂等初始化 LOS 收益管理规则（演示：未来第 8~10 天大床房最少连住 2 晚） */
    private void ensureLosRules(LocalDate today) {
        if (losRuleMapper.selectCount(null) > 0) {
            return;
        }
        LosRule rule = new LosRule();
        rule.setRoomTypeId(findByCode("DB").getId());
        rule.setStartDate(today.plusDays(7));
        rule.setEndDate(today.plusDays(9));
        rule.setMinNights(2);
        rule.setMaxNights(30);
        rule.setRemark("周末档收益管理演示：最少连住 2 晚");
        losRuleMapper.insert(rule);
        log.info("LOS 收益管理规则初始化完成（DB 房型 +7~+9 天最少连住 2 晚）");
    }

    /** 周末（周五/周六晚）价格上浮 20%，演示价格日历的差异化定价能力 */
    private BigDecimal weekendPrice(RoomType rt, LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        boolean weekend = dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY;
        BigDecimal price = weekend ? rt.getBasePrice().multiply(WEEKEND_PREMIUM) : rt.getBasePrice();
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    private void ensureDemoOrders(LocalDate today) {
        if (orderMapper.selectCount(null) > 0) {
            return;
        }
        RoomType db = findByCode("DB");
        RoomType tw = findByCode("TW");
        RoomType su = findByCode("SU");
        if (db == null || tw == null || su == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String day = today.format(DAY);

        // ① 已确认、今日入住 → 现场演示"办理入住"
        DemoOrder o1 = createDemo(db, today, today.plusDays(2), 1, "张伟", "13800138001", "310101199001011234",
                OrderStatus.CONFIRMED, now.minusHours(3), "HB" + day + "100001");
        writeLog(o1.order.getId(), null, OrderStatus.PENDING_PAYMENT, "guest", "提交预订，已锁定房量", o1.createdAt);
        writeLog(o1.order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED, "guest", "模拟支付成功",
                o1.createdAt.plusMinutes(10));

        // ② 已入住（在住）
        DemoOrder o2 = createDemo(tw, today, today.plusDays(1), 1, "李娜", "13800138002", "310101199201015678",
                OrderStatus.CHECKED_IN, now.minusHours(2), "HB" + day + "100002");
        writeLog(o2.order.getId(), null, OrderStatus.PENDING_PAYMENT, "guest", "提交预订，已锁定房量", o2.createdAt);
        writeLog(o2.order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED, "guest", "模拟支付成功",
                o2.createdAt.plusMinutes(8));
        writeLog(o2.order.getId(), OrderStatus.CONFIRMED, OrderStatus.CHECKED_IN, "frontdesk", "办理入住",
                o2.createdAt.plusMinutes(20));

        // ③ 今日退房（已完成）
        DemoOrder o3 = createDemo(db, today.minusDays(1), today, 1, "王强", "13800138003", "310101199301019876",
                OrderStatus.CHECKED_OUT, now.minusDays(1), "HB" + today.minusDays(1).format(DAY) + "100003");
        writeLog(o3.order.getId(), null, OrderStatus.PENDING_PAYMENT, "guest", "提交预订，已锁定房量", o3.createdAt);
        writeLog(o3.order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED, "guest", "模拟支付成功",
                o3.createdAt.plusMinutes(5));
        writeLog(o3.order.getId(), OrderStatus.CONFIRMED, OrderStatus.CHECKED_IN, "frontdesk", "办理入住",
                o3.createdAt.plusMinutes(15));
        writeLog(o3.order.getId(), OrderStatus.CHECKED_IN, OrderStatus.CHECKED_OUT, "frontdesk", "办理退房",
                o3.createdAt.plusDays(1).minusHours(3));

        // ④ 待支付（3 天后入住）
        DemoOrder o4 = createDemo(su, today.plusDays(3), today.plusDays(5), 1, "赵敏", "13800138004", "310101199401012345",
                OrderStatus.PENDING_PAYMENT, now.minusHours(1), "HB" + day + "100004");
        writeLog(o4.order.getId(), null, OrderStatus.PENDING_PAYMENT, "guest", "提交预订，已锁定房量", o4.createdAt);

        log.info("演示订单初始化完成：张伟(今日入住待办理) / 李娜(在住) / 王强(今日退房) / 赵敏(待支付)");
    }

    private RoomType findByCode(String code) {
        return roomTypeMapper.selectOne(new LambdaQueryWrapper<RoomType>().eq(RoomType::getCode, code));
    }

    /** 创建演示订单并同步占用库存（演示订单同样遵守占房规则，保证房态一致） */
    private DemoOrder createDemo(RoomType rt, LocalDate checkIn, LocalDate checkOut, int rooms,
                                 String name, String phone, String idNo,
                                 OrderStatus status, LocalDateTime createdAt, String orderNo) {
        List<LocalDate> dates = DateRules.nightDates(checkIn, checkOut);
        Map<LocalDate, BigDecimal> calendar = priceMapper.selectList(new LambdaQueryWrapper<DailyPrice>()
                        .eq(DailyPrice::getRoomTypeId, rt.getId())
                        .in(DailyPrice::getBizDate, dates))
                .stream().collect(Collectors.toMap(DailyPrice::getBizDate, DailyPrice::getPrice, (a, b) -> a));

        HotelOrder order = new HotelOrder();
        order.setOrderNo(orderNo);
        order.setRoomTypeId(rt.getId());
        order.setCheckInDate(checkIn);
        order.setCheckOutDate(checkOut);
        order.setRoomCount(rooms);
        order.setGuestName(name);
        order.setGuestPhone(phone);
        order.setGuestIdNo(idNo);
        order.setTotalPrice(PricingPolicy.total(rt, dates, calendar, rooms));
        order.setStatus(status.name());
        order.setRequestId("demo-" + orderNo);
        order.setCreatedAt(createdAt);
        order.setUpdatedAt(createdAt);
        orderMapper.insert(order);

        List<DailyInventory> rows = inventoryMapper.selectList(new LambdaQueryWrapper<DailyInventory>()
                .eq(DailyInventory::getRoomTypeId, rt.getId())
                .in(DailyInventory::getBizDate, dates));
        for (DailyInventory row : rows) {
            row.setSoldRooms(row.getSoldRooms() + rooms);
            inventoryMapper.updateById(row);
        }
        return new DemoOrder(order, createdAt);
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

    @AllArgsConstructor
    private static class DemoOrder {
        private final HotelOrder order;
        private final LocalDateTime createdAt;
    }
}
