package com.qiyun.hotel.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.config.AppProperties;
import com.qiyun.hotel.domain.DateRules;
import com.qiyun.hotel.domain.LosPolicy;
import com.qiyun.hotel.domain.PricingPolicy;
import com.qiyun.hotel.domain.entity.DailyInventory;
import com.qiyun.hotel.domain.entity.DailyPrice;
import com.qiyun.hotel.domain.entity.LosRule;
import com.qiyun.hotel.domain.entity.RoomType;
import com.qiyun.hotel.dto.AvailabilityVO;
import com.qiyun.hotel.dto.NightPriceVO;
import com.qiyun.hotel.mapper.DailyInventoryMapper;
import com.qiyun.hotel.mapper.DailyPriceMapper;
import com.qiyun.hotel.mapper.LosRuleMapper;
import com.qiyun.hotel.mapper.RoomTypeMapper;

import lombok.RequiredArgsConstructor;

/**
 * 房态查询：给定入住区间，返回各房型的可售余量与每晚价格明细。
 * 可售余量 = 区间内各日剩余房量的最小值（区间内每天都必须有房才可订）。
 */
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final RoomTypeMapper roomTypeMapper;
    private final DailyInventoryMapper inventoryMapper;
    private final DailyPriceMapper priceMapper;
    private final LosRuleMapper losRuleMapper;
    private final AppProperties props;
    private final Clock clock;

    public List<AvailabilityVO> query(LocalDate checkIn, LocalDate checkOut) {
        LocalDate today = LocalDate.now(clock);
        DateRules.validate(checkIn, checkOut, today,
                props.getBooking().getMaxStayNights(), props.getBooking().getMaxAdvanceDays());
        List<LocalDate> dates = DateRules.nightDates(checkIn, checkOut);

        List<RoomType> types = roomTypeMapper.selectList(new LambdaQueryWrapper<RoomType>()
                .eq(RoomType::getActive, true)
                .orderByAsc(RoomType::getBasePrice));

        List<AvailabilityVO> result = new ArrayList<>();
        for (RoomType rt : types) {
            List<DailyInventory> invs = inventoryMapper.selectList(new LambdaQueryWrapper<DailyInventory>()
                    .eq(DailyInventory::getRoomTypeId, rt.getId())
                    .in(DailyInventory::getBizDate, dates));

            // LOS 收益管理提示：区间内生效的最小连住晚数（0=无限制），前端展示"需连住 N 晚"
            List<LosRule> losRules = losRuleMapper.selectList(new LambdaQueryWrapper<LosRule>()
                    .eq(LosRule::getRoomTypeId, rt.getId())
                    .le(LosRule::getStartDate, checkOut.minusDays(1))
                    .ge(LosRule::getEndDate, checkIn));
            int minLos = LosPolicy.resolve(losRules, checkIn, checkOut).min;
            Map<LocalDate, BigDecimal> calendar = priceMapper.selectList(new LambdaQueryWrapper<DailyPrice>()
                            .eq(DailyPrice::getRoomTypeId, rt.getId())
                            .in(DailyPrice::getBizDate, dates))
                    .stream()
                    .collect(Collectors.toMap(DailyPrice::getBizDate, DailyPrice::getPrice, (a, b) -> a));

            // 任一日期库存未初始化则视为不可售（演示数据启动时自动补齐）
            int remaining = invs.size() == dates.size()
                    ? invs.stream().mapToInt(i -> i.getTotalRooms() - i.getSoldRooms()).min().orElse(0)
                    : 0;

            List<NightPriceVO> priceDetails = new ArrayList<>();
            for (LocalDate date : dates) {
                NightPriceVO np = new NightPriceVO();
                np.setDate(date);
                np.setPrice(PricingPolicy.priceFor(rt, date, calendar));
                priceDetails.add(np);
            }

            AvailabilityVO vo = new AvailabilityVO();
            vo.setRoomTypeId(rt.getId());
            vo.setCode(rt.getCode());
            vo.setName(rt.getName());
            vo.setAmenities(rt.getAmenities());
            vo.setBasePrice(rt.getBasePrice());
            vo.setNights(dates.size());
            vo.setMinLosNights(minLos);
            vo.setRemaining(remaining);
            vo.setPriceDetails(priceDetails);
            vo.setTotalPrice(PricingPolicy.total(rt, dates, calendar, 1));
            result.add(vo);
        }
        return result;
    }
}
