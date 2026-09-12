package com.qiyun.hotel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Swagger 文档配置，访问 /swagger-ui.html。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hotelOpenApi() {
        return new OpenAPI().info(new Info()
                .title("栖云酒店预订管理系统 API")
                .description("预订 / 订单查询 / 办理入住。核心设计：按日期行锁库存防超卖、订单状态机、幂等下单。")
                .version("1.0.0"));
    }
}
