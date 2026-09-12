package com.qiyun.hotel;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.qiyun.hotel.config.AppProperties;


/**
 * 栖云酒店预订管理系统入口。
 *
 * <p>技术栈：Spring Boot 2.7 + Java 8 + MyBatis-Plus + H2(演示)/MySQL(生产)。
 * 启动即完成幂等初始化（房型、未来 60 天房量库存与价格日历、演示订单），
 * 前端为 Spring Boot 直出的静态 SPA（见 src/main/resources/static）。
 */
@SpringBootApplication
@MapperScan("com.qiyun.hotel.mapper")
@EnableConfigurationProperties(AppProperties.class)
public class HotelApplication {

    public static void main(String[] args) {
        SpringApplication.run(HotelApplication.class, args);
    }
}
