package com.qiyun.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Arrays;
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
import com.qiyun.hotel.common.BizException;
import com.qiyun.hotel.common.ErrorCode;
import com.qiyun.hotel.domain.entity.DailyInventory;
import com.qiyun.hotel.dto.CreateOrderRequest;
import com.qiyun.hotel.service.BookingService;

/**
 * 并发防超卖测试（本系统最关键的用例）：
 * 10 个线程同时抢仅剩的 1 间房（入住 2 晚），断言：
 * - 恰好 1 单成功、9 单被 INSUFFICIENT_INVENTORY 拒绝；
 * - 任意一晚 sold ≤ total（不变量），且最终 sold = total。
 *
 * 机制验证点：SELECT ... FOR UPDATE 行锁 + 事务内"校验-扣减"，并发事务在库存行上串行化。
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private BookingService bookingService;

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
    @DisplayName("10 线程抢 1 间房：恰 1 单成功，不超卖")
    void tenThreadsRaceForOneRoom_noOversell() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        // 线程池会吞掉任务异常，用容器显式捕获并断言，避免测试假绿
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
                    req.setCheckOut(today.plusDays(2)); // 入住 2 晚，两晚都必须有房
                    req.setRoomCount(1);
                    req.setGuestName("并发客人" + idx);
                    req.setGuestPhone("1390000" + String.format("%04d", idx));
                    req.setRequestId(UUID.randomUUID().toString());
                    bookingService.create(req);
                    success.incrementAndGet();
                } catch (BizException e) {
                    if (e.getErrorCode() == ErrorCode.INSUFFICIENT_INVENTORY) {
                        rejected.incrementAndGet();
                    } else {
                        unexpected.set(new IllegalStateException("并发下单出现非预期业务异常", e));
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

        // 恰好 1 单成功，9 单因房量不足被拒
        assertEquals(1, success.get(), "成功单数应为 1");
        assertEquals(9, rejected.get(), "被拒单数应为 9");

        // 不变量：每晚 sold 不超过 total；最终 = 1(预占) + 1(抢到) = total
        for (LocalDate date : Arrays.asList(today, today.plusDays(1))) {
            DailyInventory inv = inventoryMapper.selectOne(new LambdaQueryWrapper<DailyInventory>()
                    .eq(DailyInventory::getRoomTypeId, roomTypeId)
                    .eq(DailyInventory::getBizDate, date));
            assertTrue(inv.getSoldRooms() <= inv.getTotalRooms(), "发生超卖：" + date);
            assertEquals(2, inv.getSoldRooms(), "最终售出应为 total：" + date);
        }
        assertEquals(1, orderMapper.selectCount(null), "最终只应有一笔订单");
    }
}
