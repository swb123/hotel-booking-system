package com.qiyun.hotel.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qiyun.hotel.common.BizException;
import com.qiyun.hotel.common.ErrorCode;

/**
 * 日期规则单元测试：间夜数计算、合法性校验、占房日期序列。
 */
class DateRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    @Test
    void validRangePasses() {
        DateRules.validate(TODAY, TODAY.plusDays(2), TODAY, 30, 45);
    }

    @Test
    void checkInInPastRejected() {
        BizException e = assertThrows(BizException.class,
                () -> DateRules.validate(TODAY.minusDays(1), TODAY.plusDays(1), TODAY, 30, 45));
        assertEquals(ErrorCode.INVALID_DATE_RANGE, e.getErrorCode());
        assertTrue(e.getMessage().contains("早于今天"));
    }

    @Test
    void checkOutMustBeAfterCheckIn() {
        BizException e = assertThrows(BizException.class,
                () -> DateRules.validate(TODAY, TODAY, TODAY, 30, 45));
        assertEquals(ErrorCode.INVALID_DATE_RANGE, e.getErrorCode());
    }

    @Test
    void stayTooLongRejected() {
        BizException e = assertThrows(BizException.class,
                () -> DateRules.validate(TODAY, TODAY.plusDays(31), TODAY, 30, 45));
        assertEquals(ErrorCode.INVALID_DATE_RANGE, e.getErrorCode());
    }

    @Test
    void beyondBookingWindowRejected() {
        // 离店日超出"最多提前 45 天"窗口（保证落在库存预生成范围内）
        BizException e = assertThrows(BizException.class,
                () -> DateRules.validate(TODAY.plusDays(44), TODAY.plusDays(46), TODAY, 30, 45));
        assertEquals(ErrorCode.INVALID_DATE_RANGE, e.getErrorCode());
        assertTrue(e.getMessage().contains("45 天"));
    }

    @Test
    void nightsIsCheckOutMinusCheckIn() {
        assertEquals(2, DateRules.nights(TODAY, TODAY.plusDays(2)));
        assertEquals(1, DateRules.nights(TODAY, TODAY.plusDays(1)));
    }

    @Test
    void nightDatesExcludesCheckOutDay() {
        List<LocalDate> dates = DateRules.nightDates(TODAY, TODAY.plusDays(3));
        assertEquals(3, dates.size());
        assertEquals(TODAY, dates.get(0));
        assertEquals(TODAY.plusDays(2), dates.get(2));
    }
}
