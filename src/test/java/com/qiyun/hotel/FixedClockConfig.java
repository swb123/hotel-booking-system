package com.qiyun.hotel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试固定时钟：所有"今天"的判断锚定 2026-09-07，用例与真实日期解耦、可重复执行。
 */
@TestConfiguration
public class FixedClockConfig {

    public static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    @Bean
    @Primary
    public Clock fixedClock() {
        Instant instant = LocalDateTime.of(2026, 9, 7, 10, 0, 0).toInstant(ZoneOffset.ofHours(8));
        return Clock.fixed(instant, ZoneId.of("Asia/Shanghai"));
    }
}
