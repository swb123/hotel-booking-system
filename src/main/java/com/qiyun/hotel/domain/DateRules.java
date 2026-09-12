package com.qiyun.hotel.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.qiyun.hotel.common.BizException;
import com.qiyun.hotel.common.ErrorCode;

/**
 * 预订日期规则（纯领域逻辑，无 IO，可独立单测）：
 * - 入住日期不能早于今天
 * - 离店必须晚于入住（酒店以"晚"为计价单位，跨晚数 = 订单间夜数）
 * - 单次入住上限
 * - 预订窗口：离店日不得超过 maxAdvanceDays 天之后（保证落在库存预生成窗口内）
 */
public final class DateRules {

    private DateRules() {
    }

    public static void validate(LocalDate checkIn, LocalDate checkOut, LocalDate today,
                                int maxStayNights, int maxAdvanceDays) {
        if (checkIn.isBefore(today)) {
            throw new BizException(ErrorCode.INVALID_DATE_RANGE, "入住日期不能早于今天");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new BizException(ErrorCode.INVALID_DATE_RANGE, "离店日期必须晚于入住日期");
        }
        if (nights(checkIn, checkOut) > maxStayNights) {
            throw new BizException(ErrorCode.INVALID_DATE_RANGE, "单次入住最长 " + maxStayNights + " 晚");
        }
        if (checkOut.isAfter(today.plusDays(maxAdvanceDays))) {
            throw new BizException(ErrorCode.INVALID_DATE_RANGE, "仅开放未来 " + maxAdvanceDays + " 天内的预订");
        }
    }

    /** 间夜数：[checkIn, checkOut) 的天数 */
    public static long nights(LocalDate checkIn, LocalDate checkOut) {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    /** 需要占用库存的日期序列：[checkIn, checkOut)，不含离店日 */
    public static List<LocalDate> nightDates(LocalDate checkIn, LocalDate checkOut) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = checkIn; d.isBefore(checkOut); d = d.plusDays(1)) {
            dates.add(d);
        }
        return dates;
    }
}
