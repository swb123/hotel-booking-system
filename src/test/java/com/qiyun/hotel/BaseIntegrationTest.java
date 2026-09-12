package com.qiyun.hotel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiyun.hotel.domain.entity.DailyInventory;
import com.qiyun.hotel.domain.entity.DailyPrice;
import com.qiyun.hotel.domain.entity.RoomType;
import com.qiyun.hotel.mapper.DailyInventoryMapper;
import com.qiyun.hotel.mapper.DailyPriceMapper;
import com.qiyun.hotel.mapper.HotelOrderMapper;
import com.qiyun.hotel.mapper.RoomTypeMapper;

/**
 * 集成测试基类：
 * - 独立 H2 内存库（MySQL 兼容模式，与演示库同一套 SQL/锁语义）；
 * - 关闭演示数据种子，由用例自建夹具；
 * - 固定时钟（2026-09-07）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hoteltest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.seed.enabled=false"
})
@Import(FixedClockConfig.class)
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected RoomTypeMapper roomTypeMapper;

    @Autowired
    protected DailyInventoryMapper inventoryMapper;

    @Autowired
    protected DailyPriceMapper priceMapper;

    @Autowired
    protected HotelOrderMapper orderMapper;

    @Autowired
    protected com.qiyun.hotel.mapper.LosRuleMapper losRuleMapper;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("DELETE FROM order_log");
        jdbc.execute("DELETE FROM hotel_order");
        jdbc.execute("DELETE FROM los_rule");
        jdbc.execute("DELETE FROM daily_price");
        jdbc.execute("DELETE FROM daily_inventory");
        jdbc.execute("DELETE FROM room_type");
    }

    /** 预置 LOS 收益管理规则：某房型 [start, end] 日期段内连住晚数 ∈ [min, max] */
    protected void seedLosRule(Long roomTypeId, String start, String end, int min, int max) {
        com.qiyun.hotel.domain.entity.LosRule rule = new com.qiyun.hotel.domain.entity.LosRule();
        rule.setRoomTypeId(roomTypeId);
        rule.setStartDate(LocalDate.parse(start));
        rule.setEndDate(LocalDate.parse(end));
        rule.setMinNights(min);
        rule.setMaxNights(max);
        rule.setRemark("test");
        losRuleMapper.insert(rule);
    }

    /**
     * 预置一个房型：today..+3 共 4 晚库存与价格（价格默认 price 元/晚，可用 pricesOverride 逐晚覆盖）。
     *
     * @return 房型 id
     */
    protected Long seedRoomType(String code, String name, String price, int totalRooms, String... pricesOverride) {
        RoomType type = new RoomType();
        type.setCode(code);
        type.setName(name);
        type.setBasePrice(new BigDecimal(price));
        type.setTotalRooms(totalRooms);
        type.setAmenities("测试设施");
        type.setActive(true);
        roomTypeMapper.insert(type);

        for (int i = 0; i < 4; i++) {
            LocalDate d = FixedClockConfig.TODAY.plusDays(i);
            DailyInventory inv = new DailyInventory();
            inv.setRoomTypeId(type.getId());
            inv.setBizDate(d);
            inv.setTotalRooms(totalRooms);
            inv.setSoldRooms(0);
            inventoryMapper.insert(inv);

            DailyPrice p = new DailyPrice();
            p.setRoomTypeId(type.getId());
            p.setBizDate(d);
            p.setPrice(new BigDecimal(i < pricesOverride.length ? pricesOverride[i] : price));
            priceMapper.insert(p);
        }
        return type.getId();
    }

    protected void setSold(Long roomTypeId, LocalDate date, int sold) {
        DailyInventory inv = inventoryMapper.selectOne(new LambdaQueryWrapper<DailyInventory>()
                .eq(DailyInventory::getRoomTypeId, roomTypeId)
                .eq(DailyInventory::getBizDate, date));
        inv.setSoldRooms(sold);
        inventoryMapper.updateById(inv);
    }

    protected int soldOf(Long roomTypeId, LocalDate date) {
        DailyInventory inv = inventoryMapper.selectOne(new LambdaQueryWrapper<DailyInventory>()
                .eq(DailyInventory::getRoomTypeId, roomTypeId)
                .eq(DailyInventory::getBizDate, date));
        return inv == null ? 0 : inv.getSoldRooms();
    }

    /** 构造下单请求体（Map，供 MockMvc + ObjectMapper 序列化） */
    protected Map<String, Object> createReq(String requestId, String checkIn, String checkOut, Long roomTypeId) {
        Map<String, Object> req = new HashMap<>();
        req.put("roomTypeId", roomTypeId);
        req.put("checkIn", checkIn);
        req.put("checkOut", checkOut);
        req.put("roomCount", 1);
        req.put("guestName", "测试客人");
        req.put("guestPhone", "13912345678");
        req.put("guestIdNo", "310101199001011234");
        req.put("requestId", requestId);
        return req;
    }
}
