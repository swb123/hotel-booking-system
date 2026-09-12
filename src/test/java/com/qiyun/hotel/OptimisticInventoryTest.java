package com.qiyun.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.common.BizException;
import com.qiyun.hotel.common.ErrorCode;
import com.qiyun.hotel.domain.entity.DailyInventory;
import com.qiyun.hotel.domain.entity.HotelOrder;
import com.qiyun.hotel.dto.CreateOrderRequest;
import com.qiyun.hotel.service.BookingService;
import com.qiyun.hotel.service.OrderService;

/**
 * 乐观锁（条件原子更新）模式的防超卖测试：
 * 与 ConcurrencyTest（悲观锁模式）同规格的 10 线程抢房场景，
 * 证明"无锁等待"的策略同样不超卖——冲突由 UPDATE 的 WHERE 条件与受影响行数判定。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hoteltest_opt;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.seed.enabled=false",
        "app.booking.inventory-mode=optimistic"
})
@Import(FixedClockConfig.class)
class OptimisticInventoryTest extends BaseIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private OrderService orderService;

    private Long roomTypeId;
    private LocalDate today;

    @BeforeEach
    void seedOneRoomLeft() {
        today = FixedClockConfig.TODAY;
        roomTypeId = seedRoomType("DB", "大床房", "368.00", 2);
        setSold(roomTypeId, today, 1);               // 仅剩 1 间
        setSold(roomTypeId, today.plusDays(1), 1);   // 第二晚同样仅剩 1 间
    }

    @Test
    @DisplayName("乐观锁模式：10 线程抢 1 间房，恰 1 单成功、不超卖")
    void tenThreadsRaceForOneRoom_noOversell_optimistic() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    CreateOrderRequest req = new CreateOrderRequest();
                    req.setRoomTypeId(roomTypeId);
                    req.setCheckIn(today);
                    req.setCheckOut(today.plusDays(2));
                    req.setRoomCount(1);
                    req.setGuestName("乐观并发" + idx);
                    req.setGuestPhone("1390003" + String.format("%04d", idx));
                    req.setRequestId(UUID.randomUUID().toString());
                    bookingService.create(req);
                    success.incrementAndGet();
                } catch (BizException e) {
                    if (e.getErrorCode() == ErrorCode.INSUFFICIENT_INVENTORY) {
                        rejected.incrementAndGet();
                    } else {
                        unexpected.set(new IllegalStateException("非预期业务异常", e));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程就绪超时");
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发下单执行超时");
        if (unexpected.get() != null) {
            throw new AssertionError("并发线程出现非预期异常", unexpected.get());
        }

        // 与悲观锁模式同规格的断言：恰 1 成功、9 被拒、不超卖
        assertEquals(1, success.get(), "成功单数应为 1");
        assertEquals(9, rejected.get(), "被拒单数应为 9");
        for (LocalDate date : java.util.Arrays.asList(today, today.plusDays(1))) {
            DailyInventory inv = inventoryMapper.selectOne(new LambdaQueryWrapper<DailyInventory>()
                    .eq(DailyInventory::getRoomTypeId, roomTypeId)
                    .eq(DailyInventory::getBizDate, date));
            assertTrue(inv.getSoldRooms() <= inv.getTotalRooms(), "发生超卖：" + date);
            assertEquals(2, inv.getSoldRooms(), "最终售出应为 total：" + date);
        }
        assertEquals(1, orderMapper.selectCount(null), "最终只应有一笔订单");
    }

    @Test
    @DisplayName("乐观锁模式：全流程（下单→支付→取消释放）与幂等语义正常")
    void fullFlowAndIdempotency_optimistic() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setRoomTypeId(roomTypeId);
        req.setCheckIn(today);
        req.setCheckOut(today.plusDays(2));
        req.setRoomCount(1);
        req.setGuestName("乐观流程");
        req.setGuestPhone("13900039999");
        req.setRequestId("opt-idem-" + UUID.randomUUID());

        // 下单成功
        String orderNo = bookingService.create(req).getOrderNo();
        assertEquals(2, soldOf(roomTypeId, today)); // 1(预占) + 1(本单)

        // 幂等：同 requestId 重复提交返回同一订单、不重复扣减
        assertEquals(orderNo, bookingService.create(req).getOrderNo());
        assertEquals(2, soldOf(roomTypeId, today));

        // 支付 → 取消 → 库存释放
        assertEquals("CONFIRMED", orderService.pay(orderNo).getStatus());
        assertEquals("CANCELLED", orderService.cancel(orderNo).getStatus());
        assertEquals(1, soldOf(roomTypeId, today)); // 归还 1 间，回到预占状态
        assertEquals(1, soldOf(roomTypeId, today.plusDays(1)));

        // 满房拒单：此时仅剩 1 间，订 2 间应被条件原子更新拒绝
        CreateOrderRequest over = new CreateOrderRequest();
        over.setRoomTypeId(roomTypeId);
        over.setCheckIn(today);
        over.setCheckOut(today.plusDays(2));
        over.setRoomCount(2);
        over.setGuestName("乐观超订");
        over.setGuestPhone("13900038888");
        over.setRequestId("opt-over-" + UUID.randomUUID());
        try {
            bookingService.create(over);
            throw new AssertionError("2 间应被拒单");
        } catch (BizException e) {
            assertEquals(ErrorCode.INSUFFICIENT_INVENTORY, e.getErrorCode());
        }
        // 第一单已取消，超订被拒 → 非取消订单应为 0；总订单数仍为 1（仅第一单）
        assertEquals(0, orderMapper.selectCount(new LambdaQueryWrapper<HotelOrder>()
                .ne(HotelOrder::getStatus, "CANCELLED")), "超订不应落单");
        assertEquals(1, orderMapper.selectCount(null), "总订单数应为 1");
    }
}
