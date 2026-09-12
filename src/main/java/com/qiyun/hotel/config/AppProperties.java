package com.qiyun.hotel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务配置（application.yml 中 app.* 前缀）。
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Seed seed = new Seed();
    private Booking booking = new Booking();

    @Data
    public static class Seed {
        /** 启动时是否执行幂等初始化 */
        private boolean enabled = true;
        /** 是否写入演示订单（便于现场演示办理入住/退房） */
        private boolean demoOrders = true;
        /** 预生成库存与价格日历的天数 */
        private int inventoryDays = 60;
    }

    @Data
    public static class Booking {
        /** 单次入住最长晚数 */
        private int maxStayNights = 30;
        /** 单笔订单最大房间数 */
        private int maxRoomsPerOrder = 5;
        /** 预订窗口：最多提前多少天（保证不越过库存预生成窗口） */
        private int maxAdvanceDays = 45;
        /**
         * 库存扣减策略：pessimistic=行锁(FOR UPDATE，默认) / optimistic=条件原子更新
         * 两种模式均有并发防超卖测试证明（ConcurrencyTest / OptimisticInventoryTest），
         * 生产选择建议：低竞争用 optimistic（零锁等待吞吐更高），热点日期段切 Redis Lua（见 docs/02 §5）。
         */
        private String inventoryMode = "pessimistic";
    }
}
