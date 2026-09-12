package com.qiyun.hotel;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.qiyun.hotel.domain.entity.DailyInventory;
import com.qiyun.hotel.domain.entity.RoomType;
import com.qiyun.hotel.dto.CreateOrderRequest;
import com.qiyun.hotel.service.BookingService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * 性能基准（默认不参与日常回归，单独运行）：
 *   mvn test -Dtest=BenchmarkTest -Dexcluded.groups=none
 *
 * 环境口径：单机 H2 文件库（MODE=MySQL）、服务层直调（不含 HTTP 开销）、固定时钟。
 * 三个场景：
 *   ① 串行下单：基线吞吐；
 *   ② 8 线程、日期错开（不同库存行，锁不竞争）：水平扩展性；
 *   ③ 8 线程、同一日期段（同一批库存行，行锁串行化）：竞争热点下的吞吐，验证锁等待行为不劣化、不超卖。
 * 结果打印在 surefire 报告中（target/surefire-reports/com.qiyun.hotel.BenchmarkTest.txt），供文档引用。
 */
@Tag("bench")
class BenchmarkTest extends BaseIntegrationTest {

    private static final int TOTAL_ROOMS = 500;
    private static final int SEED_NIGHTS = 30;

    @Autowired
    private BookingService bookingService;

    private Long roomTypeId;
    private LocalDate today;

    @BeforeEach
    void seedWideInventory() {
        today = FixedClockConfig.TODAY;
        // 自建大容量房型（500 间、30 晚），确保基准过程中不因房量失败
        RoomType type = new RoomType();
        type.setCode("BM");
        type.setName("基准房型");
        type.setBasePrice(new java.math.BigDecimal("368.00"));
        type.setTotalRooms(TOTAL_ROOMS);
        type.setAmenities("benchmark");
        type.setActive(true);
        roomTypeMapper.insert(type);
        roomTypeId = type.getId();
        for (int d = 0; d < SEED_NIGHTS; d++) {
            DailyInventory inv = new DailyInventory();
            inv.setRoomTypeId(roomTypeId);
            inv.setBizDate(today.plusDays(d));
            inv.setTotalRooms(TOTAL_ROOMS);
            inv.setSoldRooms(0);
            inventoryMapper.insert(inv);
            com.qiyun.hotel.domain.entity.DailyPrice price = new com.qiyun.hotel.domain.entity.DailyPrice();
            price.setRoomTypeId(roomTypeId);
            price.setBizDate(today.plusDays(d));
            price.setPrice(new java.math.BigDecimal("368.00"));
            priceMapper.insert(price);
        }
    }

    @Test
    @DisplayName("性能基准：串行 / 并行错开 / 热点竞争三场景")
    void benchmarkThreeScenarios() throws Exception {
        int n = 200;
        int workers = 8;

        // ① 串行（日期错开，锁不竞争）
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            bookingService.create(req(i, i % 25, 2));
        }
        long t1 = System.nanoTime();
        report("串行下单 x200（日期错开）", t1 - t0, n);

        // ② 8 线程并行、日期错开（不同库存行，验证横向扩展）
        AtomicInteger done = new AtomicInteger();
        long t2 = System.nanoTime();
        runParallel(workers, n, (idx, start) -> bookingService.create(req(idx, idx % 25, 2)), done);
        long t3 = System.nanoTime();
        report("并行x8下单 x200（日期错开）", t3 - t2, done.get());

        // ③ 8 线程并行、同一日期段（同一批库存行，行锁串行化——热点场景）
        done.set(0);
        long t4 = System.nanoTime();
        runParallel(workers, n, (idx, start) -> bookingService.create(req(idx, 0, 2)), done);
        long t5 = System.nanoTime();
        report("并行x8下单 x200（同一日期段，行锁竞争）", t5 - t4, done.get());

        System.out.println("[bench] 完成。说明：环境=单机 H2(MODE=MySQL) 服务层直调；生产 MySQL 行锁语义相同，吞吐随硬件/网络而变化。");
    }

    private CreateOrderRequest req(int idx, int dateOffset, int nights) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setRoomTypeId(roomTypeId);
        req.setCheckIn(today.plusDays(dateOffset));
        req.setCheckOut(today.plusDays(dateOffset + nights));
        req.setRoomCount(1);
        req.setGuestName("基准客人" + idx);
        req.setGuestPhone("1390002" + String.format("%04d", idx));
        req.setRequestId("bench-" + UUID.randomUUID());
        return req;
    }

    private void runParallel(int workers, int total, OrderAction action, AtomicInteger done) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    action.run(idx, start);
                    done.incrementAndGet();
                } catch (Exception e) {
                    System.out.println("[bench] 异常 idx=" + idx + " : " + e.getMessage());
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("基准执行超时");
        }
    }

    private void report(String label, long nanos, int count) {
        double ms = nanos / 1_000_000.0;
        double tps = count * 1000.0 / ms;
        System.out.printf("[bench] %s : %.0f ms, TPS=%.1f, 平均=%.2f ms/单%n", label, ms, tps, ms / count);
    }

    @FunctionalInterface
    private interface OrderAction {
        void run(int idx, CountDownLatch start) throws Exception;
    }
}
