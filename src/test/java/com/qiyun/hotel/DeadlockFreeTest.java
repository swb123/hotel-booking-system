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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.domain.entity.DailyInventory;
import com.qiyun.hotel.dto.CreateOrderRequest;
import com.qiyun.hotel.service.BookingService;/**
 * 死锁验证测试：
 * 20 个线程同时预订相互重叠但起点不同的日期段（如 [d0,d3)、[d1,d4)…），
 * 任意两笔订单的库存行锁集合都会交叉 —— 若加锁顺序不固定（ORDER BY biz_date 缺失），
 * 交叉加锁会在数据库层形成死锁/锁等待超时。
 * 断言：全部线程在限时内完成、全部成功、且无锁超时异常 —— 证明固定加锁顺序生效。
 */
class DeadlockFreeTest extends BaseIntegrationTest {

    @Autowired
    private BookingService bookingService;

    private Long roomTypeId;
    private LocalDate today;

    @BeforeEach
    void seedWideInventory() {
        today = FixedClockConfig.TODAY;
        // 200 间物理房、4 晚基础库存；订单最远用到 +12 天，补齐剩余日期
        roomTypeId = seedRoomType("DB", "大床房", "368.00", 200);
        for (int d = 4; d <= 12; d++) {
            DailyInventory inv = new DailyInventory();
            inv.setRoomTypeId(roomTypeId);
            inv.setBizDate(today.plusDays(d));
            inv.setTotalRooms(200);
            inv.setSoldRooms(0);
            inventoryMapper.insert(inv);
        }
    }

    @Test
    @DisplayName("20 线程交叉锁定重叠日期段：全部完成、无死锁、不超卖")
    void overlappingDateRanges_noDeadlock() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    CreateOrderRequest req = new CreateOrderRequest();
                    req.setRoomTypeId(roomTypeId);
                    // 起点错开 0..9 天、各住 3 晚 → 锁集合两两交叉
                    req.setCheckIn(today.plusDays(idx % 10));
                    req.setCheckOut(today.plusDays(idx % 10 + 3));
                    req.setRoomCount(1);
                    req.setGuestName("交叉并发" + idx);
                    req.setGuestPhone("1390001" + String.format("%04d", idx));
                    req.setRequestId(UUID.randomUUID().toString());
                    bookingService.create(req);
                    success.incrementAndGet();
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
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发下单超时——疑似死锁或锁等待超时");
        if (unexpected.get() != null) {
            throw new AssertionError("并发线程出现非预期异常（含锁超时）", unexpected.get());
        }
        assertEquals(threads, success.get(), "全部订单应成功（房量充足场景只验证锁行为）");

        // 不变量兜底：每晚 sold ≤ total
        for (int d = 0; d < 13; d++) {
            LocalDate date = today.plusDays(d);
            DailyInventory inv = inventoryMapper.selectOne(new LambdaQueryWrapper<DailyInventory>()
                    .eq(DailyInventory::getRoomTypeId, roomTypeId)
                    .eq(DailyInventory::getBizDate, date));
            assertTrue(inv.getSoldRooms() <= inv.getTotalRooms(), "发生超卖：" + date);
        }
    }
}
