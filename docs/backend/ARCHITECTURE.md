# 后端架构与模块

后端是一个 Java 21、Spring Boot 4、MyBatis 应用，单个 Maven 工程，共享进程和 MySQL 数据库。HTTP 请求从各域的 Controller 进入，定时任务由 `automation` 调用各域的 Scheduler。[应用入口](../../backend/src/main/java/com/trade/TradeApplication.java)

## 模块目录

代码位于 `backend/src/main/java/com/trade/`，测试在 `backend/src/test/java/com/trade/` 下按包对应。每个一级包的 `package-info.java` 说明该模块的职责。

| 模块 | 负责什么 | 从哪里读代码 |
| --- | --- | --- |
| `automation` | 后台任务、持久化模块开关和启动时的数据库迁移 | [任务登记](../../backend/src/main/java/com/trade/automation/application/task/AutomationTaskRegistrar.java) · [模块开关恢复](../../backend/src/main/java/com/trade/automation/application/task/ModuleControlService.java) |
| `trading` | OKX 行情、策略、风控、PAPER 账本、LIVE 订单与对账、回测和 Trading AI | [交易决策](../../backend/src/main/java/com/trade/trading/application/strategy/TradingStrategyEngine.java) · [订单对账](../../backend/src/main/java/com/trade/trading/application/order/OrderReconciliationService.java) · [策略顾问](../../backend/src/main/java/com/trade/trading/application/strategy/TradingAiAdvisor.java) |
| `polymarket` | 市场筛选、AI 决策、执行预留与审计 | [决策与执行用例](../../backend/src/main/java/com/trade/polymarket/application/service/AiPolymarketService.java) |
| `weibo` | OAuth、热点采集、草稿、审核、发布和账号配额 | [微博发布用例](../../backend/src/main/java/com/trade/weibo/application/service/WeiboPostService.java) |
| `x` | 内容规则、版本化草稿、审核、发布和账号配额 | [X 发布用例](../../backend/src/main/java/com/trade/x/application/service/XPostService.java) |
| `client` | AI、OKX、Polymarket、微博、Telegram 和 X 的协议、签名与传输 | [客户端目录](../../backend/src/main/java/com/trade/client/) · [AI 供应商选择](../../backend/src/main/java/com/trade/client/ai/config/AiClientConfiguration.java) |
| `ai` | 各业务域共用的 AI 响应解析失败审计 | [审计接口](../../backend/src/main/java/com/trade/ai/port/AiResponseParseErrorSink.java) |
| `common` | 无 I/O 的共享函数，包括交易数值计算 | [TradingMath](../../backend/src/main/java/com/trade/common/support/TradingMath.java) |

## 分层与依赖

业务域先分层，再按能力建包。`automation` 与 `ai` 也按需采用这一结构。`client` 按外部服务组织，业务域各自维护提示词、响应解析和业务校验。

| 包 | 职责 | 示例 |
| --- | --- | --- |
| `interfaces` | 接收 HTTP、SSE 或定时触发，转换外部请求 | `TradingController`、`TradingScheduler` |
| `application` | 安排调用顺序、事务和审计，将配置、供应商 DTO 转换为领域输入 | `RiskControlService`、`OrderSettlementService` |
| `domain` | 订单状态、资金计算、策略规则与不变量 | `OrderChange`、`OrderSizingRules`、`ThresholdDecisionPolicy` |
| `port` | 定义用例需要的下单、存储、行情和审核等接口，与四层目录并列 | `TradingBroker`、`TradingStateStore`、`HumanReviewGateway` |
| `infrastructure` | 实现上述接口，装配 Spring Bean，接入 MyBatis、Redis、交易所或外部进程 | `MyBatisTradingOrderRepository`、`OkxOrderGateway` |

`interfaces` 调用 `application`，纯领域规则独立于 Spring、MyBatis 和供应商类型。HTTP 层通过用例访问数据，Mapper 和持久化 Row 留在适配器内。四个业务域之间的调度由 `automation` 协调，`client`、`ai` 和 `common` 只向业务域提供共享能力。包路径、依赖方向和 Mapper XML 类型由架构测试校验。[架构测试](../../backend/src/test/java/com/trade/architecture/PackageArchitectureTest.java)

## 启动与后台任务

Flyway 在应用就绪前完成迁移。`AutomationTaskRegistrar` 登记 `trading`、`trading-ai`、`polymarket`、`weibo`、`x` 五个任务。应用就绪时，`ModuleControlService` 先恢复数据库中的操作员选择，`AutomationTaskManager` 再启动启用的任务。没有保存选择时，自动启动跟随模块开关，显式的 `auto-start` 可覆盖默认值。[迁移装配](../../backend/src/main/java/com/trade/automation/infrastructure/config/DatabaseMigrationConfiguration.java) · [任务执行](../../backend/src/main/java/com/trade/automation/application/task/AutomationTaskManager.java)

各循环在上一次执行结束后按固定延迟调度，同一任务内的循环互斥。交易任务和领导租约心跳使用独立调度池。功能控制台保存的模块选择可以跨重启恢复，任务 API 的临时启停只作用于当前实例。[后台任务操作](OPERATIONS.md#后台任务操作)

## OKX 交易调用链

```text
TradingScheduler → TradingTriggerService → TradingStrategyEngine
  → 当前策略 → TradingBrokerRouter
    ├─ PaperBroker   → PaperOrderExecutionService → 模拟账本
    └─ OkxLiveBroker → LiveOrderExecutionService  → 订单预留 → OkxOrderGateway

OrderReconciliationService → 按标的、clOrdId 和已有的交易所订单 ID 查询 OKX
  → OrderSettlementService → 订单状态与历史、累计成交账本、仓位成本、风险状态
```

- **实盘提交与对账。** 每笔订单有持久化幂等键和确定的 `clOrdId`。提交前检查模块、领导权及资金停止状态。结果不明进入 `SUBMIT_UNKNOWN`，阻断新单，按原标识查询。结算在同一事务内应用累计成交量和费用相对上次记录的差额，重放同一快照不会重复计仓。[实盘执行](../../backend/src/main/java/com/trade/trading/application/execution/LiveOrderExecutionService.java) · [成交结算](../../backend/src/main/java/com/trade/trading/application/order/OrderSettlementService.java)
- **资金状态。** MySQL 持久化受管理仓位、成本和风险。交易所余额用于核对，`HALTED` 持续阻止新单，恢复需要对账、核对挂单、当前 revision 和人工确认。[资金停止与恢复](OPERATIONS.md#资金级停止与恢复)
- **策略与模拟。** 活动策略按模式和标的共享。策略维护、PAPER 成交与 LIVE 订单预留在短事务内核对版本，交易所请求在锁外执行。PAPER 使用公共行情和独立的模拟账本。Trading AI 通过自己的任务评估已有策略，自动调整受独立开关约束。[策略参数与模拟资金](../trading/SIMULATION.md) · [Trading AI](../trading/AI.md)
- **行情与回测。** REST 与 WebSocket 将行情送入有界队列，各处理器独立写入 MySQL 或 Redis。HTTP 接口经 SSE 向驾驶舱推送增量。回测从独立 API 触发，通过 `HistoricalCandleSource` 合并本次 REST 数据和已有 K 线；回测运行记录保存在进程内，重启后清空。[事件总线](../../backend/src/main/java/com/trade/trading/infrastructure/event/BoundedTradingEventBus.java) · [回测](../trading/BACKTEST.md) · [队列指标与告警](OBSERVABILITY.md)

## Polymarket 与内容发布

Polymarket 实盘决策先建立审计记录，再采集行情并调用 AI。下单前取得数据库执行预留，通过市场约束、地域和执行检查后调用 Python 桥接。确认不完整时保留预留，重启后仍会阻断后续实盘下单。恢复步骤和脚本契约分别见[未决执行恢复](OPERATIONS.md#polymarket-未决执行恢复)和[桥接工具](../../tools/polymarket/README.md)。

微博和 X 分别保存草稿、正文版本、审核决定、轮询进度与发布尝试。共享的 Telegram 客户端负责传输，各域的审核适配器核对聊天、审核人、消息和版本。批准后由所属域检查发布开关、账号与额度，再调用平台客户端。发布进入 `UNKNOWN` 时须人工核对平台结果。[微博工作流](../publishing/WEIBO_WORKFLOW.md) · [X 工作流](../publishing/X_WORKFLOW.md) · [Telegram 协议](../publishing/TELEGRAM_API.md)

## 配置与持久化

后端配置入口是 [application.yml](../../backend/src/main/resources/application.yml)。数据库表、Mapper 与代码按域对应，策略画像和近期决策记忆另存于 `data/trading-state.json`。运行中的资金与订单记录在 MySQL。

| 目录 | 用途 |
| --- | --- |
| [mapper/](../../backend/src/main/resources/mapper/) | 各域的 MyBatis XML，与 Mapper 接口、Row 类型对应 |
| [db/schema/](../../backend/src/main/resources/db/schema/) | 各模块当前的完整表结构，供查阅和离线校验 |
| [db/migration/](../../backend/src/main/resources/db/migration/) · [db/migration-support/](../../backend/src/main/resources/db/migration-support/) | Flyway 启动迁移及 Java 迁移依赖的固定 SQL |
| [db/upgrade/](../../backend/src/main/resources/db/upgrade/README.md) | 接入 Flyway 之前的旧库补丁及前置条件，按目标库结构选用 |

修改表结构、包路径或配置后，按对应范围验证。[数据库开发规范](../../backend/src/main/resources/db/README.md#主键开发规范) · [后端贡献指南](../../backend/CONTRIBUTING.md#测试与质量门禁)
