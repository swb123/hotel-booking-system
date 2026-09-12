package com.qiyun.hotel.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qiyun.hotel.common.ApiResponse;
import com.qiyun.hotel.dto.FrontDeskStatsVO;
import com.qiyun.hotel.dto.OrderVO;
import com.qiyun.hotel.service.FrontDeskService;
import com.qiyun.hotel.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/frontdesk")
@RequiredArgsConstructor
@Tag(name = "前台管理")
public class FrontDeskController {

    private final OrderService orderService;
    private final FrontDeskService frontDeskService;

    @GetMapping("/orders")
    @Operation(summary = "前台订单列表（可按状态/入住日期筛选）")
    public ApiResponse<List<OrderVO>> orders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(frontDeskService.orders(status, date));
    }

    @PostMapping("/orders/{orderNo}/check-in")
    @Operation(summary = "办理入住（未到入住日期将被拒绝）")
    public ApiResponse<OrderVO> checkIn(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.checkIn(orderNo));
    }

    @PostMapping("/orders/{orderNo}/check-out")
    @Operation(summary = "办理退房")
    public ApiResponse<OrderVO> checkOut(@PathVariable String orderNo) {
        return ApiResponse.ok(orderService.checkOut(orderNo));
    }

    @GetMapping("/stats")
    @Operation(summary = "今日经营统计（入住/退房/在住/营收/入住率）")
    public ApiResponse<FrontDeskStatsVO> stats() {
        return ApiResponse.ok(frontDeskService.stats());
    }
}
