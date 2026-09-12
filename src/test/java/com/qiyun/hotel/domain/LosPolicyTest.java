package com.qiyun.hotel.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qiyun.hotel.common.BizException;
import com.qiyun.hotel.common.ErrorCode;
import com.qiyun.hotel.domain.entity.LosRule;

/**
 * LOS（Length Of Stay）收益管理策略单元测试：
 * 规则重叠判定、MinLOS/MaxLOS 约束、多规则并集、非重叠忽略。
 */
class LosPolicyTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 14);
    private static final LocalDate D5 = LocalDate.of(2026, 9, 18);

    private LosRule rule(String start, String end, int min, int max) {
        LosRule r = new LosRule();
        r.setStartDate(LocalDate.parse(start));
        r.setEndDate(LocalDate.parse(end));
        r.setMinNights(min);
        r.setMaxNights(max);
        return r;
    }

    @Test
    void noRulesPasses() {
        LosPolicy.validate(Collections.emptyList(), D1, D1.plusDays(1));
    }

    @Test
    void nonOverlappingRuleIgnored() {
        // 规则在 9-20~9-22，入住 9-14~9-16，不重叠 → 不受限
        List<LosRule> rules = Arrays.asList(rule("2026-09-20", "2026-09-22", 3, 30));
        LosPolicy.validate(rules, D1, D1.plusDays(2));
    }

    @Test
    void minLosViolationRejected() {
        List<LosRule> rules = Arrays.asList(rule("2026-09-14", "2026-09-16", 2, 30));
        BizException e = assertThrows(BizException.class,
                () -> LosPolicy.validate(rules, D1, D1.plusDays(1))); // 只住 1 晚
        assertEquals(ErrorCode.LOS_MIN_NOT_MET, e.getErrorCode());
        assertTrue(e.getMessage().contains("至少 2 晚"));
    }

    @Test
    void minLosSatisfied() {
        List<LosRule> rules = Arrays.asList(rule("2026-09-14", "2026-09-16", 2, 30));
        LosPolicy.validate(rules, D1, D1.plusDays(2)); // 2 晚 ✓
    }

    @Test
    void maxLosViolationRejected() {
        List<LosRule> rules = Arrays.asList(rule("2026-09-14", "2026-09-18", 1, 3));
        BizException e = assertThrows(BizException.class,
                () -> LosPolicy.validate(rules, D1, D1.plusDays(4))); // 4 晚
        assertEquals(ErrorCode.LOS_MAX_EXCEEDED, e.getErrorCode());
    }

    @Test
    void partialOverlapStillApplies() {
        // 规则只覆盖入住区间的一部分（后半程：9-15 起），同样生效
        List<LosRule> rules = Arrays.asList(rule("2026-09-15", "2026-09-18", 3, 30));
        // 住 9-14~9-16 两晚（14、15），第 15 晚落入规则区间 → 要求 3 晚 → 拒绝
        BizException e = assertThrows(BizException.class,
                () -> LosPolicy.validate(rules, D1, D1.plusDays(2)));
        assertEquals(ErrorCode.LOS_MIN_NOT_MET, e.getErrorCode());
    }

    @Test
    void multipleOverlappingRulesTakeUnion() {
        // 规则1: min 2；规则2: max 3 → 并集 [2,3]；住 4 晚违反 max
        List<LosRule> rules = Arrays.asList(
                rule("2026-09-14", "2026-09-20", 2, 30),
                rule("2026-09-14", "2026-09-20", 1, 3));
        BizException e = assertThrows(BizException.class,
                () -> LosPolicy.validate(rules, D1, D1.plusDays(4)));
        assertEquals(ErrorCode.LOS_MAX_EXCEEDED, e.getErrorCode());

        LosPolicy.validate(rules, D1, D1.plusDays(2)); // 2 晚 ∈ [2,3] ✓
    }

    @Test
    void resolveReturnsDefaultWithoutRules() {
        LosPolicy.LosConstraint c = LosPolicy.resolve(new ArrayList<>(), D1, D1.plusDays(2));
        assertEquals(1, c.min);
        assertEquals(Integer.MAX_VALUE, c.max);
    }
}
