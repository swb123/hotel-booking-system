package com.qiyun.hotel.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qiyun.hotel.domain.entity.RoomType;

/**
 * 计价策略单元测试：价格日历优先、缺失回退基准价、总价 = Σ每晚价 × 间数。
 */
class PricingPolicyTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 7);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 8);
    private static final LocalDate D3 = LocalDate.of(2026, 9, 9);

    private RoomType roomType;
    private Map<LocalDate, BigDecimal> calendar;

    @BeforeEach
    void setUp() {
        roomType = new RoomType();
        roomType.setBasePrice(new BigDecimal("368.00"));

        calendar = new HashMap<>();
        calendar.put(D1, new BigDecimal("441.60")); // 周末价（日历覆盖基准价）
        calendar.put(D2, new BigDecimal("368.00"));
        // D3 无日历 → 回退基准价
    }

    @Test
    void calendarPriceWinsWhenPresent() {
        assertEquals(0, new BigDecimal("441.60").compareTo(PricingPolicy.priceFor(roomType, D1, calendar)));
    }

    @Test
    void fallbackToBasePriceWhenCalendarMissing() {
        assertEquals(0, new BigDecimal("368.00").compareTo(PricingPolicy.priceFor(roomType, D3, calendar)));
    }

    @Test
    void totalIsPerNightSumTimesRoomCount() {
        // (441.60 + 368.00 + 368.00) × 2 = 2355.20
        BigDecimal total = PricingPolicy.total(roomType, Arrays.asList(D1, D2, D3), calendar, 2);
        assertEquals(0, new BigDecimal("2355.20").compareTo(total));
    }
}
