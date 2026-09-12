package com.qiyun.hotel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 预订全流程集成测试（HTTP 层，MockMvc）：
 * 下单 → 支付 → 入住 → 退房 / 取消释放库存 / 幂等 / 非法状态迁移 / 提前入住拒绝 / 查询与统计。
 */
class BookingFlowIntegrationTest extends BaseIntegrationTest {

    private static final String TODAY = "2026-09-07";
    private static final String TOMORROW = "2026-09-08";
    private static final String DAY_AFTER = "2026-09-09";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Long roomTypeId;

    @BeforeEach
    void seedFixture() {
        // 大床房 2 间物理房，368 元/晚
        roomTypeId = seedRoomType("DB", "大床房", "368.00", 2);
    }

    // ---------- 下单 ----------

    @Test
    @DisplayName("下单成功：订单落库、状态待支付、库存扣减、金额正确")
    void createOrderSuccess() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json(createReq("req-000001", TODAY, DAY_AFTER, roomTypeId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.orderNo").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.statusLabel").value("待支付"))
                .andExpect(jsonPath("$.data.totalPrice").value(736.00))
                .andExpect(jsonPath("$.data.nights").value(2))
                .andExpect(jsonPath("$.data.logs.length()").value(1));

        // 两晚库存各扣 1
        assertEquals(1, soldOf(roomTypeId, LocalDate.parse(TODAY)));
        assertEquals(1, soldOf(roomTypeId, LocalDate.parse(TOMORROW)));
        assertEquals(1, orderMapper.selectCount(null));
    }

    @Test
    @DisplayName("幂等：同一 requestId 重复提交返回同一订单，库存不重复扣减")
    void createOrderIdempotent() throws Exception {
        String body = json(createReq("req-idem", TODAY, DAY_AFTER, roomTypeId));
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));

        assertEquals(1, orderMapper.selectCount(null)); // 只落了一单
        assertEquals(1, soldOf(roomTypeId, LocalDate.parse(TODAY))); // 库存只扣一次
    }

    @Test
    @DisplayName("房量不足：409 INSUFFICIENT_INVENTORY，不落单")
    void createOrderInsufficient() throws Exception {
        setSold(roomTypeId, LocalDate.parse(TODAY), 2); // 满房
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json(createReq("req-full", TODAY, TOMORROW, roomTypeId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("房量不足")));
        assertEquals(0, orderMapper.selectCount(null));
    }

    @Test
    @DisplayName("非法日期：离店不晚于入住 → 400")
    void createOrderInvalidDates() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json(createReq("req-date", TODAY, TODAY, roomTypeId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    @DisplayName("过去日期：入住早于今天 → 400")
    void createOrderPastDate() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json(createReq("req-past", "2026-09-06", TOMORROW, roomTypeId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    @DisplayName("房型不存在 → 404")
    void createOrderUnknownRoomType() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json(createReq("req-notfound", TODAY, TOMORROW, 99999L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_FOUND"));
    }

    @Test
    @DisplayName("参数校验：手机号格式错误 → 400 INVALID_PARAM")
    void createOrderInvalidPhone() throws Exception {
        Map<String, Object> req = createReq("req-phone", TODAY, TOMORROW, roomTypeId);
        req.put("guestPhone", "123");
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    // ---------- 状态流转 ----------

    @Test
    @DisplayName("完整入住链：下单→支付→入住→退房")
    void fullStayFlow() throws Exception {
        String orderNo = createAndGetOrderNo("req-flow", TODAY, TOMORROW);

        mockMvc.perform(post("/api/orders/" + orderNo + "/pay")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        mockMvc.perform(post("/api/frontdesk/orders/" + orderNo + "/check-in")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHECKED_IN"));
        mockMvc.perform(post("/api/frontdesk/orders/" + orderNo + "/check-out")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHECKED_OUT"));

        // 退房后再办理入住 → 409 非法迁移
        mockMvc.perform(post("/api/frontdesk/orders/" + orderNo + "/check-in"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("重复支付 → 409 非法状态迁移")
    void doublePayRejected() throws Exception {
        String orderNo = createAndGetOrderNo("req-2pay", TODAY, TOMORROW);
        mockMvc.perform(post("/api/orders/" + orderNo + "/pay")).andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/" + orderNo + "/pay"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("未到入住日期 → 400 EARLY_CHECK_IN")
    void earlyCheckInRejected() throws Exception {
        String orderNo = createAndGetOrderNo("req-early", TOMORROW, DAY_AFTER); // 明天才入住
        mockMvc.perform(post("/api/orders/" + orderNo + "/pay")).andExpect(status().isOk());
        mockMvc.perform(post("/api/frontdesk/orders/" + orderNo + "/check-in"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EARLY_CHECK_IN"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未到入住日期")));
    }

    @Test
    @DisplayName("取消释放库存：取消后余量恢复，可再次预订")
    void cancelReleasesInventory() throws Exception {
        String orderNo = createAndGetOrderNo("req-cancel", TODAY, TOMORROW);
        assertEquals(1, soldOf(roomTypeId, LocalDate.parse(TODAY)));

        mockMvc.perform(post("/api/orders/" + orderNo + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertEquals(0, soldOf(roomTypeId, LocalDate.parse(TODAY))); // 库存归还

        // 房态恢复可再订
        mockMvc.perform(get("/api/availability").param("checkIn", TODAY).param("checkOut", TOMORROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].remaining").value(2));
    }

    @Test
    @DisplayName("提前退房释放剩余间夜库存（退房当日及之前的间夜视为已消费）")
    void earlyCheckOutReleasesRemainingNights() throws Exception {
        String orderNo = createAndGetOrderNo("req-earlyout", TODAY, "2026-09-10"); // 3 晚：07/08/09
        mockMvc.perform(post("/api/orders/" + orderNo + "/pay")).andExpect(status().isOk());
        mockMvc.perform(post("/api/frontdesk/orders/" + orderNo + "/check-in")).andExpect(status().isOk());
        assertEquals(1, soldOf(roomTypeId, LocalDate.parse(TODAY)));
        assertEquals(1, soldOf(roomTypeId, LocalDate.parse(TOMORROW)));
        assertEquals(1, soldOf(roomTypeId, LocalDate.parse(DAY_AFTER)));

        mockMvc.perform(post("/api/frontdesk/orders/" + orderNo + "/check-out"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHECKED_OUT"));

        // 退房日之后的间夜被释放；退房当日保留（已消费）
        assertEquals(1, soldOf(roomTypeId, LocalDate.parse(TODAY)));
        assertEquals(0, soldOf(roomTypeId, LocalDate.parse(TOMORROW)));
        assertEquals(0, soldOf(roomTypeId, LocalDate.parse(DAY_AFTER)));
    }

    @Test
    @DisplayName("已入住订单不可取消 → 409")
    void cancelAfterCheckInRejected() throws Exception {
        String orderNo = createAndGetOrderNo("req-nocancel", TODAY, TOMORROW);
        mockMvc.perform(post("/api/orders/" + orderNo + "/pay")).andExpect(status().isOk());
        mockMvc.perform(post("/api/frontdesk/orders/" + orderNo + "/check-in")).andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/" + orderNo + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    // ---------- 查询 / 房态 / 统计 ----------

    @Test
    @DisplayName("手机号查单：返回订单列表（新单在前）")
    void queryByPhone() throws Exception {
        createAndGetOrderNo("req-query-1", TODAY, TOMORROW);
        createAndGetOrderNo("req-query-2", TOMORROW, DAY_AFTER);
        mockMvc.perform(get("/api/orders").param("phone", "13912345678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].checkIn").value(TOMORROW)); // 后创建的在前
    }

    @Test
    @DisplayName("订单详情含完整状态时间线")
    void detailTimeline() throws Exception {
        String orderNo = createAndGetOrderNo("req-timeline", TODAY, TOMORROW);
        mockMvc.perform(post("/api/orders/" + orderNo + "/pay")).andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/" + orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logs.length()").value(2))
                .andExpect(jsonPath("$.data.logs[0].toStatusLabel").value("待支付"))
                .andExpect(jsonPath("$.data.logs[1].toStatusLabel").value("已确认"));
    }

    @Test
    @DisplayName("房态查询：余量随占用递减，含每晚价格明细")
    void availabilityReflectsInventory() throws Exception {
        mockMvc.perform(get("/api/availability").param("checkIn", TODAY).param("checkOut", DAY_AFTER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].remaining").value(2))
                .andExpect(jsonPath("$.data[0].priceDetails.length()").value(2));

        createAndGetOrderNo("req-avail", TODAY, DAY_AFTER);
        mockMvc.perform(get("/api/availability").param("checkIn", TODAY).param("checkOut", DAY_AFTER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].remaining").value(1));
    }

    @Test
    @DisplayName("前台统计：今日入住/在住/营收/入住率口径正确（待支付不计营收）")
    void frontDeskStats() throws Exception {
        String orderNo = createAndGetOrderNo("req-stats", TODAY, TOMORROW);
        mockMvc.perform(post("/api/orders/" + orderNo + "/pay")).andExpect(status().isOk());
        mockMvc.perform(post("/api/frontdesk/orders/" + orderNo + "/check-in")).andExpect(status().isOk());
        // 再下一笔待支付订单：不应计入营业额
        createAndGetOrderNo("req-stats-pending", TODAY, TOMORROW);

        mockMvc.perform(get("/api/frontdesk/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayCheckIn").value(1))
                .andExpect(jsonPath("$.data.inHouse").value(1))
                .andExpect(jsonPath("$.data.todayRevenue").value(368.00)) // 待支付 368 被排除
                .andExpect(jsonPath("$.data.occupancyRate").value(100.0))
                .andExpect(jsonPath("$.data.roomTypes.length()").value(1))
                .andExpect(jsonPath("$.data.roomTypes[0].sold").value(2));
    }

    @Test
    @DisplayName("过期订单（已超过离店日）不可办理入住 → 409 CHECK_IN_EXPIRED")
    void checkInAfterCheckOutDateRejected() throws Exception {
        String orderNo = createAndGetOrderNo("req-expired", TODAY, TOMORROW);
        mockMvc.perform(post("/api/orders/" + orderNo + "/pay")).andExpect(status().isOk());

        // 模拟时间流逝：把订单离店日期改到昨天（固定时钟下无法通过 API 造出过期单）
        com.qiyun.hotel.domain.entity.HotelOrder order = orderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.qiyun.hotel.domain.entity.HotelOrder>()
                        .eq(com.qiyun.hotel.domain.entity.HotelOrder::getOrderNo, orderNo));
        order.setCheckOutDate(java.time.LocalDate.parse(TODAY).minusDays(1));
        orderMapper.updateById(order);

        mockMvc.perform(post("/api/frontdesk/orders/" + orderNo + "/check-in"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECK_IN_EXPIRED"));
    }

    @Test
    @DisplayName("LOS 收益管理：MinLOS 违反拒单、满足放行")
    void losMinNightsEnforced() throws Exception {
        // 9-09 ~ 9-10 最少连住 2 晚
        seedLosRule(roomTypeId, "2026-09-09", "2026-09-10", 2, 30);
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json(createReq("req-los-min-x", "2026-09-09", "2026-09-10", roomTypeId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOS_MIN_NOT_MET"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("至少 2 晚")));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json(createReq("req-los-min-ok", "2026-09-09", "2026-09-11", roomTypeId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("LOS 收益管理：MaxLOS 违反拒单（校验先于库存锁定）")
    void losMaxNightsEnforced() throws Exception {
        // 9-09 ~ 9-11 最长连住 2 晚；订 3 晚 → LOS 校验在库存检查之前拦截
        seedLosRule(roomTypeId, "2026-09-09", "2026-09-11", 1, 2);
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json(createReq("req-los-max", "2026-09-09", "2026-09-12", roomTypeId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOS_MAX_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("最长可连住 2 晚")));
    }

    @Test
    @DisplayName("超出预订窗口（45 天）→ 400 INVALID_DATE_RANGE")
    void bookingWindowRejected() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json(createReq("req-window", "2026-10-23", "2026-10-24", roomTypeId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("45 天")));
    }

    @Test
    @DisplayName("前台列表按状态筛选")
    void frontDeskFilterByStatus() throws Exception {
        String orderNo = createAndGetOrderNo("req-filter", TODAY, TOMORROW);
        mockMvc.perform(get("/api/frontdesk/orders").param("status", "CHECKED_IN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/frontdesk/orders").param("status", "PENDING_PAYMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNo").value(orderNo));
    }

    // ---------- helpers ----------

    private String createAndGetOrderNo(String requestId, String checkIn, String checkOut) throws Exception {
        String body = json(createReq(requestId, checkIn, checkOut, roomTypeId));
        return objectMapper.readTree(mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .path("data").path("orderNo").asText();
    }

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }
}
