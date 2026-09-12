package com.qiyun.hotel.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.qiyun.hotel.domain.entity.RoomType;

/**
 * 计价策略（纯领域逻辑，可独立单测）：
 * - 价格日历优先：命中 daily_price 用日历价（支撑周末/节假日差异化定价），缺失回退房型基准价；
 * - 金额全程 BigDecimal，禁止浮点参与金额运算；
 * - 总价 = Σ(每晚价) × 间数。
 */
public final class PricingPolicy {

    private PricingPolicy() {
    }

    public static BigDecimal priceFor(RoomType roomType, LocalDate date, Map<LocalDate, BigDecimal> calendar) {
        BigDecimal calendarPrice = calendar.get(date);
        return calendarPrice != null ? calendarPrice : roomType.getBasePrice();
    }

    public static BigDecimal total(RoomType roomType, List<LocalDate> dates,
                                   Map<LocalDate, BigDecimal> calendar, int roomCount) {
        BigDecimal perNightSum = BigDecimal.ZERO;
        for (LocalDate date : dates) {
            perNightSum = perNightSum.add(priceFor(roomType, date, calendar));
        }
        return perNightSum.multiply(BigDecimal.valueOf(roomCount));
    }
}
