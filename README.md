# 城市社区配送机器人进楼门禁避障签收服务

外卖 / 药品 / 商超订单交给配送机器人后，本服务负责**进楼配送全链路协同**：记录订单类型、收货楼栋、
门禁授权、是否冷藏、预计送达、电量、载物仓编号与用户联系方式；机器人到小区门口申请物业门禁，
进入楼栋后与电梯系统协同选层，到达后通过短信 / 小程序通知用户取件。

**电梯满载等待策略**：机器人乘梯被电梯系统连续上报满载 3 次（阈值可配）后，服务结合
**订单时效**（预计送达/冷藏截止剩余时间）、**机器人电量**与**目标楼层高度**做出判定——
继续等待下一趟、改**人工接力**或**通知用户下楼到大堂取件**。选择人工接力时订单状态转入接力、
载物仓在现场开启交接、配送费用按楼层重新计算（平台侧原因免收接力附加费），并完整记录
接力人员身份（工号/姓名/电话）、接货时间、仓门开启时间与用户签收；商品损坏或延迟的责任按
**机器人等待 / 人工接力 / 用户取件三个阶段**自动认定。

每一次**门禁开通、路线调整、电梯请求（含满载策略判定）、避障停留、人工接力交接、费用重算、
签收、退回、客服介入与三阶段责任判定**都写入**同一配送事件流**
（Kafka topic `delivery-events` → `delivery_event` 表），设备动作与业务结果在同一事件里，
支撑物业端（通行影响）、运营端（投放效率/接力费用）、客服端（异常责任）三类视图。

## 技术栈

Java 17 · Spring Boot 3.3 · Spring Data JPA · PostgreSQL 16 · Kafka（KRaft）· Flyway · springdoc-openapi（Swagger UI）

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

启动三个容器：`postgres`(5432)、`kafka`(9092)、`app`(8080)。应用启动时自动执行 Flyway 建表与种子数据。

- Swagger UI（OpenAPI 3.x 在线文档与调试）：http://localhost:8080/swagger-ui.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs

Compose 中为演示设置了较短超时（取件超时 120s、冷藏超时 180s、电梯超时 60s），生产可通过环境变量调大。

## 本地开发启动

依赖：JDK 17+、Maven 3.8+、本地 PostgreSQL（建库 `robot_delivery`，账号 `postgres/postgres`）、本地 Kafka（9092）。

```bash
# 1. 准备依赖（示例： Debian/Ubuntu）
#    PostgreSQL: apt install postgresql && createdb robot_delivery
#    Kafka: 解压官方 tgz，KRaft 模式 kafka-storage.sh format 后 kafka-server-start.sh

# 2. 构建并运行
mvn package -DskipTests
DB_HOST=localhost DB_PORT=5432 KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  java -jar target/robot-delivery-1.0.0.jar
```

常用环境变量（均有默认值，见 `src/main/resources/application.yml`）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | localhost/5432/robot_delivery/postgres/postgres | PostgreSQL 连接 |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Kafka 地址 |
| `PICKUP_TIMEOUT_SECONDS` | 600 | 用户超时不取件时限 |
| `COLDCHAIN_TIMEOUT_SECONDS` | 2700 | 冷藏订单履约时限 |
| `ELEVATOR_TIMEOUT_SECONDS` | 180 | 电梯请求超时 |
| `LOW_BATTERY_THRESHOLD` | 20 | 低电量阈值（%） |
| `SCHEDULER_INTERVAL_MS` | 10000 | 超时巡检间隔 |
| `ELEVATOR_FULL_THRESHOLD` | 3 | 连续满载几次后触发等待策略判定 |
| `URGENT_WINDOW_SECONDS` | 600 | 距预计送达/冷藏截止不足该秒数视为时效紧迫 |
| `HIGH_FLOOR_THRESHOLD` | 10 | 目标楼层不低于该值视为高楼 |
| `DOWNSTAIRS_TIMEOUT_SECONDS` | 同取件超时 | 通知用户下楼取件后的等待时限 |
| `RELAY_SLA_SECONDS` | 300 | 人工接力（接货→送达）时效，超出算接力阶段延迟 |
| `BASE_FEE_CENTS` / `PER_FLOOR_FEE_CENTS` / `COLD_CHAIN_FEE_CENTS` | 500/30/200 | 基础配送费/每层加价/冷藏附加费（分） |
| `RELAY_SURCHARGE_CENTS` | 300 | 人工接力附加费（分，平台侧原因触发免收） |

## 测试账号

| 账号 | 密码 | 角色 | 用途 |
|---|---|---|---|
| `admin` | `admin123` | ADMIN | 全部权限 |
| `property` | `property123` | PROPERTY | 物业端：门禁审批、夜间规则、通行影响视图 |
| `ops` | `ops123` | OPS | 运营端：下单、分配机器人、模拟机器人/电梯上报、效率视图 |
| `cs` | `cs123` | CS | 客服端：投诉受理、工单结案、责任追踪 |

调用方式：`POST /api/auth/login` 获取 token，之后请求头携带 `Authorization: Bearer <token>`。
（演示环境密码明文存储、令牌内存保存，生产需替换为安全实现。）

种子数据：社区 `阳光社区`(id=1，夜间 22:00-07:00 限制运行) / `绿洲家园`(id=2)；
楼栋 `1栋`(id=1) / `2栋`(id=2) / `5栋`(id=3)；机器人 `RB-001`(id=1) / `RB-002`(id=2，低电量 15%) / `RB-003`(id=3)，
每台机器人配 A（冷藏）/B（普通）载物仓。

## 本题验证方式

### 方式一：自动化冒烟脚本（推荐）

应用启动后执行（覆盖主流程 + 全部异常场景，含电梯连续满载三策略、人工接力交接闭环与
三阶段责任判定 + 三端视图，共 123 项断言）：

```bash
./scripts/smoke.sh            # 默认打 http://localhost:8080
```

> 脚本中的超时类场景（超时未取 / 冷藏超时 / 电梯超时）依赖较短超时配置。
> 用 compose 启动时无需调整（已内置演示参数）；本地运行时请显式指定：
> `PICKUP_TIMEOUT_SECONDS=30 COLDCHAIN_TIMEOUT_SECONDS=45 ELEVATOR_TIMEOUT_SECONDS=20 DOWNSTAIRS_TIMEOUT_SECONDS=30 SCHEDULER_INTERVAL_MS=5000 java -jar ...`

### 方式二：Swagger UI 手动验证

打开 http://localhost:8080/swagger-ui.html ，先调 `POST /api/auth/login`（如 `ops/ops123`），
点 `Authorize` 填入 `Bearer <token>`，再依次调用各接口。

### 方式三：curl 手动走一遍主流程

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"ops","password":"ops123"}' | jq -r .token)
AUTH="Authorization: Bearer $TOKEN"

# 下单（外卖、1栋、不需冷藏）
OID=$(curl -s -X POST localhost:8080/api/orders -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "orderType":"FOOD","communityId":1,"buildingId":1,"unitNo":"2单元","roomNo":"901",
  "coldChain":false,"estimatedDeliveryAt":"2026-12-31T23:59:00",
  "userName":"张三","userPhone":"13800000001"}' | jq -r .id)

curl -s -X POST localhost:8080/api/deliveries/$OID/assign        -H "$AUTH" -H 'Content-Type: application/json' -d '{"robotId":1}'
AR=$(curl -s -X POST localhost:8080/api/deliveries/$OID/arrive-gate -H "$AUTH" -H 'Content-Type: application/json' -d '{"gateName":"南门"}' | jq -r .id)
# 物业审批门禁（换 property 账号 token）
PTOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"property","password":"property123"}' | jq -r .token)
curl -s -X POST localhost:8080/api/access-requests/$AR/approve -H "Authorization: Bearer $PTOKEN"
ER=$(curl -s -X POST localhost:8080/api/deliveries/$OID/request-elevator -H "$AUTH" -H 'Content-Type: application/json' -d '{"fromFloor":1,"toFloor":9}' | jq -r .id)
curl -s -X POST localhost:8080/api/elevator-requests/$ER/assign  -H "$AUTH" -H 'Content-Type: application/json' -d '{"carId":"1栋-客梯A"}'
curl -s -X POST localhost:8080/api/elevator-requests/$ER/complete -H "$AUTH"
curl -s -X POST localhost:8080/api/deliveries/$OID/arrive-door   -H "$AUTH"
curl -s -X POST localhost:8080/api/deliveries/$OID/open-compartment -H "$AUTH"
curl -s -X POST localhost:8080/api/deliveries/$OID/sign          -H "$AUTH" -H 'Content-Type: application/json' -d '{"signBy":"张三"}'

# 查看统一配送事件时间线（设备动作 + 业务结果在同一事件流里）
curl -s localhost:8080/api/deliveries/$OID/events -H "$AUTH" | jq '.[].eventType'
```

### 异常场景验证入口

| 异常场景 | 触发方式 | 结果（均写入统一事件流） |
|---|---|---|
| 物业门禁拒绝 | `POST /api/access-requests/{id}/deny` | `ACCESS_DENIED` + 订单 EXCEPTION + 客服工单 |
| 老人儿童挡路 | `POST /api/deliveries/{id}/obstacle` `obstacleType=ELDERLY_CHILD` | `OBSTACLE_WAIT`（含停留秒数） |
| 电梯满载（前2次） | `POST /api/elevator-requests/{id}/full` | `ELEVATOR_FULL` + `ROUTE_ADJUSTED` + 自动重新请求 |
| 连续满载3次-继续等待 | 低楼层（<`HIGH_FLOOR_THRESHOLD`）+时效宽松+电量充足 | `ELEVATOR_FULL_STRATEGY(strategy=CONTINUE_WAIT)`，自动再排一趟 |
| 连续满载3次-人工接力 | 时效紧迫（ETA/冷藏截止临近）或电量不足 | `ELEVATOR_FULL_STRATEGY(MANUAL_RELAY)` + 订单 `RELAYING` + `FEE_RECALCULATED` |
| 连续满载3次-用户下楼 | 高楼层且时效宽松、电量充足 | `ELEVATOR_FULL_STRATEGY(USER_DOWNSTAIRS)` + 订单 `WAITING_DOWNSTAIRS` + 短信/小程序通知下楼 |
| 人工接力交接 | `/api/relays/by-delivery/{id}/accept|deliver|sign` | 仓门开启→接货→送达→签收，记录人员身份与时间 |
| 三阶段责任判定 | `GET/POST /api/deliveries/{id}/liability?issue=GOODS_DAMAGED|DELIVERY_DELAY` | `LIABILITY_JUDGED` + 工单，按机器人等待/接力/用户取件定责 |
| 楼道施工 | `obstacleType=CONSTRUCTION` | `OBSTACLE_WAIT` + `ROUTE_ADJUSTED(reason=CONSTRUCTION)` |
| 机器人电量不足 | 分配时电量 < 阈值被拒(409)；配送中 `POST /api/robots/{id}/status` 低电量 | `LOW_BATTERY` + 客服工单，可 `manual-relay` 人工接力 |
| 用户超时不取 | 到门后超过 `PICKUP_TIMEOUT_SECONDS` 未签收 | `PICKUP_TIMEOUT` + 自动退回 `RETURNED` |
| 载物仓打不开 | `POST /api/deliveries/{id}/compartment-fault` | `COMPARTMENT_OPEN_FAILED` + 客服工单 |
| 冷藏订单超时 | 冷藏单超过 `COLDCHAIN_TIMEOUT_SECONDS` 未完成 | `COLDCHAIN_TIMEOUT` + 客服工单 |
| 投诉送错楼栋 | `POST /api/complaints` `complaintType=WRONG_BUILDING` | `COMPLAINT_FILED` + 客服工单；`redeliver` 改派楼栋产生 `ROUTE_ADJUSTED` |
| 物业限制夜间运行 | `PUT /api/communities/{id}/night-rule` 后夜间申请门禁 | `NIGHT_RESTRICTED` + `ACCESS_DENIED` 自动拒绝 |
| 电梯请求超时 | 请求后超过 `ELEVATOR_TIMEOUT_SECONDS` 未派梯 | `ELEVATOR_TIMEOUT` + 客服工单 |

### 电梯满载等待策略（本题核心）

机器人每次被电梯系统上报满载都会产生一条 `ELEVATOR_FULL` 事件；前 2 次按既有逻辑等待下一趟并自动
重新请求。**连续第 3 次**（`ELEVATOR_FULL_THRESHOLD` 可配）满载时，服务采集三个因子并写入
`ELEVATOR_FULL_STRATEGY` 事件（payload 中同时保留判定因子，便于复盘）：

| 因子 | 取值 | 含义 |
|---|---|---|
| `urgent` 时效 | 距预计送达（冷藏单取冷藏截止）≤`URGENT_WINDOW_SECONDS`（含已超时） | 越紧迫越需要立刻恢复履约 |
| `lowBattery` 电量 | 机器人当前电量 < `LOW_BATTERY_THRESHOLD` | 电量不足无法继续占位等待 |
| `highFloor` 楼层 | 目标楼层 ≥ `HIGH_FLOOR_THRESHOLD` | 高区上行电梯持续拥挤，下行宽松 |

判定规则：

1. **时效紧迫 或 电量不足 → 人工接力 `MANUAL_RELAY`**：最快恢复履约，机器人立即释放；
2. 时效宽松、电量充足但**高楼层 → 通知用户下楼 `USER_DOWNSTAIRS`**：用户乘下行梯到大堂取件更快；
3. 时效宽松、电量充足、**低楼层 → 继续等待 `CONTINUE_WAIT`**：再排一趟。

**人工接力的订单状态、载物仓交接与费用重算**（新表 `relay_handover` 全程留痕）：

```
RELAYING（已派发，作废未完成的电梯请求，按楼层重算费用并通知用户）
  → accept  配送员现场核验身份、开启机器人载物仓（RELAY_COMPARTMENT_OPENED）并取走商品（RELAY_HANDOVER），
            释放机器人与载物仓；记录接力人员工号/姓名/电话、仓门开启时间、接货时间、接货地点
  → deliver 配送员送达用户门口（RELAY_DELIVERED），短信/小程序通知取件
  → sign    用户签收（SIGNED, reason=MANUAL_RELAY），记录签收人与时间
```

- 身份核验：自动派单的接力必须现场登记工号+姓名；预指派配送员的现场工号必须与派单一致，否则 409 禁止开仓。
- 费用重算：配送费 = 基础费 + 每层加价×乘梯楼层数 + 冷藏附加费；**因电梯满载/低电量/设备故障等平台侧
  原因触发接力，接力附加费免收用户**，仅运营主动发起（`MANUAL`）收取 `RELAY_SURCHARGE_CENTS`。
  重算结果写入订单费用列与 `FEE_RECALCULATED` 事件。
- 通知用户下楼：订单进入 `WAITING_DOWNSTAIRS`，短信/小程序通知到大堂找机器人取件；
  用户下楼后 `arrive-door → open-compartment → sign` 完成签收；超时未下楼由巡检自动退回。

**三阶段责任判定** `GET /api/deliveries/{id}/liability?issue=GOODS_DAMAGED|DELIVERY_DELAY`
（可带 `stage` 显式指定阶段），返回阶段、责任方、判定依据与量化时间线；
`POST .../liability/report` 由客服登记并自动开立工单、写入 `LIABILITY_JUDGED` 事件：

| 异常 | 机器人等待阶段 ROBOT_WAIT | 人工接力阶段 RELAY | 用户取件阶段 USER_PICKUP |
|---|---|---|---|
| 商品损坏 GOODS_DAMAGED | ROBOT_VENDOR（载物仓保管期间） | PLATFORM（配送员搬运期间） | USER（开仓取件后） |
| 配送延迟 DELIVERY_DELAY | ELEVATOR_SYSTEM（连续满载等待） | PLATFORM（接力耗时超 `RELAY_SLA_SECONDS`） | USER（通知后超时不取） |

### 三端视图

```bash
# 物业端：机器人是否影响居民通行（避障次数/停留时长/施工绕行/夜间限制/投诉/影响等级）
curl -s "localhost:8080/api/analytics/property/passage-impact?communityId=1" -H "$AUTH"

# 运营端：按小区/楼栋/订单类型评估投放效率（单量/签收率/平均时长/超时数）
curl -s "localhost:8080/api/analytics/ops/efficiency?communityId=1&orderType=FOOD" -H "$AUTH"

# 客服端：异常责任追踪
curl -s "localhost:8080/api/cs/cases?status=OPEN" -H "$AUTH"
curl -s "localhost:8080/api/cs/cases/summary" -H "$AUTH"
```

## 实际验证结论（本仓库交付前已执行）

以下验证均在本环境真实执行通过（本地 PostgreSQL 15（临时实例）+ Kafka 4.1.2 KRaft + 本应用 jar）：

1. `mvn package` 构建通过，生成 `target/robot-delivery-1.0.0.jar`。
2. 干净数据库上应用启动成功：Flyway 执行 `V1__init.sql` + `V2__seed.sql` + `V3__elevator_full_strategy_relay.sql`，JPA 校验通过，Kafka 连接正常。
3. `./scripts/smoke.sh` 全量运行 **123 项断言全部通过**（含主流程事件时间线、10 类原异常场景、
   电梯连续满载三种策略、人工接力交接闭环（身份核验/仓门开启/接货/送达/签收/费用重算）、
   三阶段责任判定与三端视图、鉴权 401/403）。
4. Swagger UI（`/swagger-ui.html`）可访问，OpenAPI 文档输出 47 个路径（新增 `/api/relays/**` 与 `/api/deliveries/{id}/liability/**`）。
5. 另做超时专项验证：通知用户下楼后超过 `DOWNSTAIRS_TIMEOUT_SECONDS` 未取，巡检自动置 `RETURNING` 并产生
   `PICKUP_TIMEOUT(reason=DOWNSTAIRS)` 事件与客服工单。
6. 全部策略判定、接力交接、费用重算与责任判定事件均可在 Kafka topic `delivery-events` 消费并在事件时间线接口查询。

## 架构说明

```
机器人/电梯系统(模拟)        物业端            运营端            客服端
      │  上报/回调             │ 审批/规则         │ 下单/调度         │ 结案
      └──────────┬─────────────┴─────────────────┴─────────────────┘
                 ▼
        Spring Boot REST API（/api/**，Bearer 鉴权 + 角色控制）
                 │  每一次设备动作与业务结果
                 ▼
        Kafka topic: delivery-events（key=配送单ID，保证同单有序）
                 ▼
        消费者落库 delivery_event（统一配送事件流）
                 ▼
        PostgreSQL（订单/机器人/门禁/电梯/投诉/工单/通知/事件）
```

- **统一配送事件**：`DeliveryEventService.emit()` 是唯一入口，36 种事件类型（见 `EventType`），
  事件携带 `actor`（机器人/物业/电梯/用户/接力配送员/系统/运营/客服）、`reason`、`waitSeconds` 与业务 JSON。
- **超时巡检**：`TimeoutScheduler` 周期检查取件超时、用户下楼超时、冷藏超时、电梯超时，自动标记并触发客服介入。
- **状态机**：`CREATED→ASSIGNED→ACCESS_PENDING→ENTERING→(ELEVATOR_WAIT→ON_FLOOR)→WAITING_PICKUP→COMPARTMENT_OPEN→SIGNED`，
  电梯连续满载分支 `WAITING_DOWNSTAIRS`（用户下楼取件）与 `RELAYING`（人工接力，经交接单闭环），
  异常分支 `EXCEPTION / RETURNING→RETURNED / CANCELLED`，非法迁移返回 409。

## 目录结构

```
├── Dockerfile / docker-compose.yml   # 一键启动
├── scripts/smoke.sh                  # 62 项断言的冒烟验证
├── src/main/resources/application.yml
├── src/main/resources/db/migration/  # V1 建表 / V2 种子数据
└── src/main/java/com/example/robotdelivery/
    ├── event/        # 统一配送事件：Kafka 生产者/消费者/事件查询
    ├── order/        # 配送订单（类型/楼栋/冷藏/预计送达/联系方式）
    ├── delivery/     # 主流程编排：分配/避障/到门/签收/退回/人工接力/改派
    ├── access/       # 物业门禁协同（含夜间限制自动拒绝）
    ├── elevator/     # 电梯系统协同（派梯/满载计数/三次满载策略判定/超时）
    ├── relay/        # 人工接力：交接单（身份/接货/仓门/签收）与配送费用重算
    ├── liability/    # 商品损坏/延迟的机器人等待-人工接力-用户取件三阶段责任判定
    ├── robot/        # 机器人与载物仓（低电量上报）
    ├── complaint/    # 用户投诉（送错楼栋等）
    ├── cs/           # 客服工单与责任认定
    ├── analytics/    # 物业通行影响 / 运营投放效率
    ├── notification/ # 短信/小程序取件通知（模拟）
    ├── scheduler/    # 超时巡检
    └── auth/ config/ # 登录鉴权 / 全局配置
```
