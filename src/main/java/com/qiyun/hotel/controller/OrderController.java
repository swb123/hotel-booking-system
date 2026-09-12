package com.qiyun.hotel.controller;

import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.Pattern;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qiyun.hotel.common.ApiResponse;
import com.qiyun.hotel.dto.CreateOrderRequest;
import com.qiyun.hotel.dto.OrderVO;
import com.qiyun.hotel.service.BookingService;
import com.qiyun.hotel.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Validated
@Tag(name = "预订与订单")
public class OrderController {

    private final BookingService bookingService;
    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "创建预订订单（幂等：同一 requestId 重复提交返回同一订单，下单即占房）")
    public ApiResponse<OrderVO> create(@Valid @RequestBody CreateOrderRequest req) {
        return ApiResponse.ok(bookingService.create(req));
    }

    @GetMapping
    @Operation(summary = "按入住人手机号查询订单列表")
    public ApiResponse<List<OrderVO>> listByPhone(
            @RequestParam @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone) {
        return ApiResponse.ok(orderService.listByPhone(phone));
    }

    @GetMapping("/{orderNo}")
    @Operation(summary = "订单详情（含状态时间线）")
    public ApiResponse<OrderVO> detail(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.detail(orderNo));
    }

    @PostMapping("/{orderNo}/pay")
    @Operation(summary = "模拟支付（待支付 → 已确认）")
    public ApiResponse<OrderVO> pay(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.pay(orderNo));
    }

    @PostMapping("/{orderNo}/cancel")
    @Operation(summary = "取消订单（占房状态取消将释放库存）")
    public ApiResponse<OrderVO> cancel(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.cancel(orderNo));
    }
}
