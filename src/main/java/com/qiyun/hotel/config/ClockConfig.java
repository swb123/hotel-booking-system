package com.qiyun.hotel.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一时钟注入：所有"今天"的判断经由 Clock，测试中可替换为固定时钟，保证用例确定性。
 *
 * <p>时区固定为 Asia/Shanghai（与 JDBC URL / Jackson / 测试时钟保持一致）：
 * 若用 systemDefaultZone，部署在 UTC 主机上时每天 0-8 点会算出"昨天"，入住日期校验错判。
 */
@Configuration
public class ClockConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
