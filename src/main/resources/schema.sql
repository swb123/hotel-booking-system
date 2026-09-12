-- 栖云酒店预订管理系统 表结构
-- 兼容 H2(MODE=MySQL) 与 MySQL 8：同一份 DDL 两种数据库通用
-- 幂等：CREATE TABLE IF NOT EXISTS，重复执行安全。
-- 注意：索引内联在 CREATE TABLE 中——MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，
-- 而 H2 的 MySQL 兼容模式同样接受内联 KEY 语法。

-- 房型（物理房型定义，库存按日期拆到 daily_inventory）
CREATE TABLE IF NOT EXISTS room_type (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(16)  NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    base_price   DECIMAL(10,2) NOT NULL COMMENT '基准价（元/间夜）',
    total_rooms  INT          NOT NULL COMMENT '该房型物理房间总数',
    amenities    VARCHAR(255) NOT NULL DEFAULT '',
    active       TINYINT      NOT NULL DEFAULT 1,
    CONSTRAINT uk_room_type_code UNIQUE (code)
);

-- 每日库存：防超卖的并发控制单元（行锁粒度 = 房型 × 日期）
CREATE TABLE IF NOT EXISTS daily_inventory (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_type_id BIGINT NOT NULL,
    biz_date     DATE   NOT NULL,
    total_rooms  INT    NOT NULL,
    sold_rooms   INT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_inventory UNIQUE (room_type_id, biz_date),
    KEY idx_inventory_date (biz_date)
);

-- 每日价格日历：支持周末/节假日差异化定价
CREATE TABLE IF NOT EXISTS daily_price (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_type_id BIGINT NOT NULL,
    biz_date     DATE   NOT NULL,
    price        DECIMAL(10,2) NOT NULL,
    CONSTRAINT uk_price UNIQUE (room_type_id, biz_date)
);

-- LOS 收益管理约束：按"房型 × 日期段"限制最少/最多连住晚数（MinLOS/MaxLOS）
-- 例：国庆档 DB 房型 09-19 ~ 09-21 最少连住 2 晚
-- 库存仍是 Daily 粒度，LOS 是叠加在其上的约束层。
-- 领域定位（诚实标注）：行业标准中 LOS 挂在房价码（RatePlan）维度，与价格同属收益管理对象；
-- 本系统为单房价码（BAR-only）简化，规则直接挂房型。多房价码演进：
--   新增 rate_plan 表 → daily_price 与 los_rule 均挂 rate_plan_id → 预订先选房价码再校验。
CREATE TABLE IF NOT EXISTS los_rule (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_type_id BIGINT NOT NULL,
    start_date   DATE   NOT NULL,
    end_date     DATE   NOT NULL,
    min_nights   INT    NOT NULL DEFAULT 1,
    max_nights   INT    NOT NULL DEFAULT 30,
    remark       VARCHAR(128) NULL,
    KEY idx_los_room_type (room_type_id)
);

-- 订单主表（order 是保留字，故表名 hotel_order）
CREATE TABLE IF NOT EXISTS hotel_order (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no       VARCHAR(32)  NOT NULL,
    room_type_id   BIGINT       NOT NULL,
    check_in_date  DATE         NOT NULL,
    check_out_date DATE         NOT NULL,
    room_count     INT          NOT NULL,
    guest_name     VARCHAR(64)  NOT NULL,
    guest_phone    VARCHAR(20)  NOT NULL,
    guest_id_no    VARCHAR(32)  NULL,
    total_price    DECIMAL(10,2) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    request_id     VARCHAR(64)  NOT NULL COMMENT '幂等键：客户端每次下单动作生成一次',
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    CONSTRAINT uk_order_no UNIQUE (order_no),
    CONSTRAINT uk_request_id UNIQUE (request_id),
    KEY idx_order_phone (guest_phone),
    KEY idx_order_status (status),
    KEY idx_order_check_in (check_in_date)
);

-- 订单状态流水：支撑订单时间线展示，也便于审计与对账
CREATE TABLE IF NOT EXISTS order_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    from_status VARCHAR(32)  NULL,
    to_status   VARCHAR(32)  NOT NULL,
    operator    VARCHAR(32)  NOT NULL COMMENT 'guest=客人 frontdesk=前台 system=系统',
    remark      VARCHAR(255) NULL,
    created_at  DATETIME     NOT NULL,
    KEY idx_log_order (order_id)
);
