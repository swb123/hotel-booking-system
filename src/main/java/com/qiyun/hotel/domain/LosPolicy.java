package com.qiyun.hotel.domain;

import java.time.LocalDate;
import java.util.List;

import com.qiyun.hotel.common.BizException;
import com.qiyun.hotel.common.ErrorCode;
import com.qiyun.hotel.domain.entity.LosRule;

/**
 * LOS（Length Of Stay）收益管理策略（纯领域逻辑，可独立单测）：
 *
 * <p>规则模型：某房型在 [startDate, endDate] 日期段内，连住晚数 ∈ [minNights, maxNights]。
 * 入住区间 [checkIn, checkOut) 与任一规则的日期段重叠即生效；多条规则重叠时取并集
 * （min 取最大、max 取最小——最保守约束）。
 *
 * <p>典型场景：国庆/节假日 MinLOS（最少连住 N 晚，防止 1 晚单占据稀缺房晚）、
 * 旺季 MaxLOS（限制长住）。库存仍是 Daily 粒度，LOS 是叠加在库存之上的约束层，
 * 对应真实酒店系统的房价码（RatePlan）维度。
 */
public final class LosPolicy {

    private LosPolicy() {
    }

    /** 解析区间内生效的 LOS 约束（无规则时 min=1、max=不限） */
    public static LosConstraint resolve(List<LosRule> rules, LocalDate checkIn, LocalDate checkOut) {
        int min = 1;
        int max = Integer.MAX_VALUE;
        for (LosRule rule : rules) {
            // 重叠判定：[startDate, endDate] 与 [checkIn, checkOut) 有交集
            boolean overlaps = rule.getStartDate().isBefore(checkOut)
                    && !rule.getEndDate().isBefore(checkIn);
            if (overlaps) {
                min = Math.max(min, rule.getMinNights());
                max = Math.min(max, rule.getMaxNights());
            }
        }
        return new LosConstraint(min, max);
    }

    /** 校验连住晚数满足区间内所有 LOS 规则，违反抛业务异常 */
    public static void validate(List<LosRule> rules, LocalDate checkIn, LocalDate checkOut) {
        LosConstraint constraint = resolve(rules, checkIn, checkOut);
        long nights = DateRules.nights(checkIn, checkOut);
        if (nights < constraint.min) {
            throw new BizException(ErrorCode.LOS_MIN_NOT_MET,
                    "该日期段受收益管理策略限制：需连住至少 " + constraint.min + " 晚");
        }
        if (constraint.max != Integer.MAX_VALUE && nights > constraint.max) {
            throw new BizException(ErrorCode.LOS_MAX_EXCEEDED,
                    "该日期段受收益管理策略限制：最长可连住 " + constraint.max + " 晚");
        }
    }

    public static class LosConstraint {
        public final int min;
        public final int max;

        LosConstraint(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }
}
