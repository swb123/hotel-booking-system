package com.qiyun.hotel.domain.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机：显式声明合法迁移路径，是状态流转的唯一事实来源。
 *
 * <pre>
 * 待支付 ──支付──▶ 已确认 ──入住──▶ 已入住 ──退房──▶ 已退房（终态）
 *   │                │
 *   └─────取消───────┴──────▶ 已取消（终态）
 * </pre>
 *
 * 业务语义：
 * - 下单即占房（创建订单时已锁库存），取消/支付超时才释放；
 * - 任何未声明的迁移（如已入住后取消、已退房后再次入住）一律拒绝，返回 409。
 */
public enum OrderStatus {

    PENDING_PAYMENT("待支付"),
    CONFIRMED("已确认"),
    CHECKED_IN("已入住"),
    CHECKED_OUT("已退房"),
    CANCELLED("已取消");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 合法迁移表：from -> 允许到达的 to 集合 */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new HashMap<>();

    static {
        TRANSITIONS.put(PENDING_PAYMENT, of(CONFIRMED, CANCELLED));
        TRANSITIONS.put(CONFIRMED, of(CHECKED_IN, CANCELLED));
        TRANSITIONS.put(CHECKED_IN, of(CHECKED_OUT));
        TRANSITIONS.put(CHECKED_OUT, Collections.<OrderStatus>emptySet());
        TRANSITIONS.put(CANCELLED, Collections.<OrderStatus>emptySet());
    }

    private static Set<OrderStatus> of(OrderStatus... targets) {
        return new HashSet<>(Arrays.asList(targets));
    }

    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Collections.<OrderStatus>emptySet()).contains(target);
    }

    /**
     * 带防护的解析：数据库中的未知状态（脏数据/版本不一致）统一转业务异常，
     * 而不是让 IllegalArgumentException 一路抛成 500。
     */
    public static OrderStatus from(String name) {
        try {
            return valueOf(name);
        } catch (Exception e) {
            throw new com.qiyun.hotel.common.BizException(
                    com.qiyun.hotel.common.ErrorCode.INVALID_STATE_TRANSITION,
                    "订单状态异常：" + name);
        }
    }

    /** 取消时需归还库存的状态（下单即占房；已入住/已退房/已取消不触发归还） */
    public boolean releasesInventoryOnCancel() {
        return this == PENDING_PAYMENT || this == CONFIRMED;
    }
}
