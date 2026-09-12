package com.qiyun.hotel.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.common.ApiResponse;
import com.qiyun.hotel.domain.entity.RoomType;
import com.qiyun.hotel.dto.AvailabilityVO;
import com.qiyun.hotel.dto.RoomTypeVO;
import com.qiyun.hotel.mapper.RoomTypeMapper;
import com.qiyun.hotel.service.AvailabilityService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "房型与房态")
public class RoomController {

    private final RoomTypeMapper roomTypeMapper;
    private final AvailabilityService availabilityService;
    private final Clock clock;

    @GetMapping("/room-types")
    @Operation(summary = "房型列表")
    public ApiResponse<List<RoomTypeVO>> roomTypes() {
        List<RoomTypeVO> vos = roomTypeMapper.selectList(new LambdaQueryWrapper<RoomType>()
                        .eq(RoomType::getActive, true)
                        .orderByAsc(RoomType::getBasePrice))
                .stream().map(t -> {
                    RoomTypeVO vo = new RoomTypeVO();
                    vo.setId(t.getId());
                    vo.setCode(t.getCode());
                    vo.setName(t.getName());
                    vo.setBasePrice(t.getBasePrice());
                    vo.setTotalRooms(t.getTotalRooms());
                    vo.setAmenities(t.getAmenities());
                    return vo;
                }).collect(Collectors.toList());
        return ApiResponse.ok(vos);
    }

    @GetMapping("/availability")
    @Operation(summary = "房态查询：区间内各房型可售余量与每晚价格（不传日期默认今天入住、明天离店）")
    public ApiResponse<List<AvailabilityVO>> availability(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {
        LocalDate today = LocalDate.now(clock);
        // 默认窗口：不传则今明两晚；只传入住则离店=入住+1（避免窗口倒挂触发 400）
        LocalDate in = checkIn != null ? checkIn : today;
        LocalDate out = checkOut != null ? checkOut : in.plusDays(1);
        return ApiResponse.ok(availabilityService.query(in, out));
    }
}
