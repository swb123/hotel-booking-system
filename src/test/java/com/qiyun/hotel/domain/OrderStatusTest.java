package com.qiyun.hotel.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.qiyun.hotel.domain.enums.OrderStatus;

/**
 * 状态机单元测试：显式迁移表是全系统的唯一事实来源，这里穷举关键合法/非法路径。
 */
class OrderStatusTest {

    @Test
    void legalTransitions() {
        // 主链路：待支付 → 已确认 → 已入住 → 已退房
        assertTrue(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.CONFIRMED));
        assertTrue(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.CHECKED_IN));
        assertTrue(OrderStatus.CHECKED_IN.canTransitionTo(OrderStatus.CHECKED_OUT));
        // 取消分支
        assertTrue(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.CANCELLED));
    }

    @Test
    void illegalTransitions() {
        // 未支付不能入住
        assertFalse(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.CHECKED_IN));
        // 已入住不能取消、不能退回已确认
        assertFalse(OrderStatus.CHECKED_IN.canTransitionTo(OrderStatus.CANCELLED));
        assertFalse(OrderStatus.CHECKED_IN.canTransitionTo(OrderStatus.CONFIRMED));
        // 状态不可回退
        assertFalse(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.PENDING_PAYMENT));
        // 终态无出口
        assertFalse(OrderStatus.CHECKED_OUT.canTransitionTo(OrderStatus.CHECKED_IN));
        assertFalse(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.CONFIRMED));
    }

    @Test
    void releasesInventoryOnCancel() {
        assertTrue(OrderStatus.PENDING_PAYMENT.releasesInventoryOnCancel());
        assertTrue(OrderStatus.CONFIRMED.releasesInventoryOnCancel());
        assertFalse(OrderStatus.CHECKED_IN.releasesInventoryOnCancel());
        assertFalse(OrderStatus.CHECKED_OUT.releasesInventoryOnCancel());
        assertFalse(OrderStatus.CANCELLED.releasesInventoryOnCancel());
    }
}
