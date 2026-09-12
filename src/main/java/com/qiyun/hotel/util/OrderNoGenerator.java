package com.qiyun.hotel.util;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.common.BizException;
import com.qiyun.hotel.common.ErrorCode;
import com.qiyun.hotel.domain.entity.HotelOrder;
import com.qiyun.hotel.mapper.HotelOrderMapper;

/**
 * 订单号生成：HB + yyyyMMddHHmmss + 4 位随机数。
 * 唯一索引兜底，生成后查重，冲突自动重试。
 */
@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_RETRY = 5;

    private final HotelOrderMapper orderMapper;
    private final Clock clock;

    public OrderNoGenerator(HotelOrderMapper orderMapper, Clock clock) {
        this.orderMapper = orderMapper;
        this.clock = clock;
    }

    public String next() {
        for (int i = 0; i < MAX_RETRY; i++) {
            String no = "HB" + LocalDateTime.now(clock).format(FMT)
                    + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
            Long exists = orderMapper.selectCount(new LambdaQueryWrapper<HotelOrder>().eq(HotelOrder::getOrderNo, no));
            if (exists != null && exists == 0) {
                return no;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR, "订单号生成失败，请重试");
    }
}
