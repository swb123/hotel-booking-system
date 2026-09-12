# 栖云酒店预订管理系统

> 面试考题交付：*"做一个包含预定功能、订单查询、办理入住的带前后端的酒店系统"*
> **Spring Boot 2.7 + Java 8 + MyBatis-Plus + H2(演示)/MySQL(生产) + 静态 SPA 前端**

## 一键运行

```bash
bash run.sh          # 自动定位 JDK、按需构建、启动 → http://localhost:8080
```

- 产品界面：http://localhost:8080
- API 文档（Swagger）：http://localhost:8080/swagger-ui.html
- H2 控制台：http://localhost:8080/h2-console（JDBC URL 同 application.yml，用户名 sa）
- 首次启动自动初始化：3 房型 + 未来 60 天房量/价格 + 4 笔演示订单（张伟-今日入住待办理 / 李娜-在住 / 王强-今日退房 / 赵敏-待支付）

要求：JDK 8+。Maven 缺失时自动用项目自带 `./mvnw`（联网下载）。无外网也能跑（依赖已随 jar 打包，前端零 CDN）。

## 演示脚本（3 分钟）

| # | 页面 | 操作 |
|---|---|---|
| 1 | 预订房间 | 选日期 → 查询房态（周末价自动上浮 20%）→ 选房型 → 填入住人 → 下单 |
| 2 | 预订房间 | 结果页点"模拟支付"；再观察**双击提交只产生一单**（幂等） |
| 3 | 订单查询 | 手机号 13800138001 → 张伟订单 → 查看状态时间线 |
| 4 | 前台管理 | 张伟订单 → 办理入住 → 状态变"已入住"，统计卡实时更新 |
| 5 | 前台管理 | 李娜订单 → 办理退房 → 完成全状态演示 |
| 6 | 防超卖 | 预订页把某房型订到满房 → 卡片置灰，强制提交被"房量不足"拒绝 |
| 7 | 命令行 | `mvn -Dtest=ConcurrencyTest test` → 10 线程抢 1 间房恰 1 单成功的证据 |

## 功能

- **预定**：房态查询（房型×日期余量 + 每晚价格日历）、下单即占房、模拟支付、取消释放库存、幂等防重复、**LOS 收益管理（最少/最多连住晚数）**
- **订单查询**：手机号/订单号查询、完整状态时间线（提交→支付→入住→退房）
- **办理入住**：前台入住/退房（未到入住日期拒绝）、今日经营统计（入住/退房/在住/营收/入住率）

## 核心设计（详见 docs/02-技术方案.md）

1. **防超卖**：`SELECT ... FOR UPDATE` 按日期升序锁库存行 → 事务内校验-扣减；并发测试证明（10 线程抢 1 间，恰 1 成功）
2. **订单状态机**：显式迁移表（待支付→已确认→已入住→已退房 / 取消），非法迁移 409；状态流水表支撑时间线
3. **幂等下单**：`request_id` 唯一索引 + 事务回滚后返回原单
4. **领域建模**：库存粒度=房型×日期；金额 DECIMAL；计价策略纯函数（日历价优先、缺失回退基准价）

## 项目结构

```
src/main/java/com/qiyun/hotel/
├── controller/      REST 接口（预订/订单/前台）
├── service/         应用服务（事务边界）
│   └── init/        幂等数据初始化（演示数据）
├── domain/          领域层：状态机/日期规则/计价策略（纯函数）
├── mapper/          MyBatis-Plus（FOR UPDATE 行锁 SQL 在 resources/mapper/*.xml）
├── dto/ common/ config/ util/
src/main/resources/
├── application.yml          H2 演示配置（MODE=MySQL）
├── application-mysql.yml    生产 MySQL 配置
├── schema.sql               幂等建表（H2/MySQL 通用）
├── mapper/*.xml             行锁 SQL
└── static/                  前端静态 SPA（零构建零 CDN）
src/test/java/                30 个自动化用例（含并发防超卖）
docs/                         过程文档（需求/技术/测试/亮点/AI 协作）
```

## 测试

```bash
mvn test     # 47/47：领域单测 + HTTP 集成 + 并发防超卖(悲观/乐观双模式) + 无死锁 + LOS；含 JaCoCo 覆盖率
mvn test -Dtest=BenchmarkTest -Dexcluded.groups=none   # 性能基准（串行 262 TPS，数据见 docs/04）
```

库存并发控制双模式：`app.booking.inventory-mode: pessimistic（默认，行锁）| optimistic（条件原子更新，零锁等待）`，各配同规格并发测试（docs/02 §5.3）。

## 生产切换 MySQL（可选演示）

```bash
docker compose up -d
java -jar target/hotel-booking.jar --spring.profiles.active=mysql
```

## 交付物索引

| 题目要求 | 位置 |
|---|---|
| 完整的可运行代码 | 本仓库 |
| 与 AI 沟通的原始会话记录 | `transcripts/`（本会话导出） |
| 使用的 Skill | docs/05-AI协作过程.md（dataviz / code-review 等） |
| 使用的 Agent | docs/05-AI协作过程.md（前端子 Agent / 评审 Agent） |
| 过程文档 | docs/01 需求方案 · 02 技术方案 · 03 测试用例 |
| 产品效果截图 | docs/screenshots/ |
| 实现思路与技术亮点 | docs/04-实现思路与技术亮点.md |
