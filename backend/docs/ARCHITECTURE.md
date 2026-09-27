# 后端架构说明

后端采用业务域优先的模块化单体。模块职责见 [模块目录](MODULES.md)，整体选择见 [ADR-0001](adr/0001-domain-first-modular-monolith.md)，域内四层结构见 [ADR-0002](adr/0002-four-layer-module-layout.md)。

## 1. 先定位业务域，再定位层和能力

业务域包括 `trading`、`polymarket`、`story`、`textgame`、`marketplace`、`weibo`。它们保留同一个 Spring Boot 进程、Maven 构建和数据库连接；没有拆成微服务或多个 Maven module。

业务域、`automation` 和共享审计模块 `ai` 只使用以下四种一级目录，按需创建：

```text
<module>/
├─ interfaces/             # 从外部进入系统
│  ├─ web/                 # HTTP、鉴权、请求转换、异常映射
│  └─ scheduler/           # 定时触发，薄入口
├─ application/            # 一个用例如何执行
│  ├─ service/             # 小模块的用例服务及包内辅助转换
│  ├─ strategy/order/.../  # 大模块按用例能力细分
│  └─ port/                # 用例需要的出站接口
├─ domain/                 # 业务数据和纯规则
│  ├─ model/               # 稳定值、状态快照、业务枚举
│  ├─ exception/           # 跨层共享的业务异常
│  └─ order/risk/rule/.../ # 有明确归属的模型与纯规则
└─ infrastructure/         # 技术实现和装配
   ├─ persistence/         # MyBatis、Row、文件存储、Redis 实现
   ├─ broker/              # 实盘、模拟盘、回测或外部进程执行器
   ├─ market/event/.../    # 行情接入、队列、处理器等适配实现
   └─ config/             # 配置绑定、Bean 装配
```

`client` 已是按供应商组织的共享传输模块，继续使用 `client/okx`、`client/weibo`、`client/ai` 等目录；`common/support` 只容纳无业务归属的纯函数，不强行给这两个技术模块套四层空目录。

## 2. 依赖方向与现有边界

```text
interfaces -> application -> domain
                    |
                    v
             application.port <- infrastructure implementation

automation -> 各业务域的 scheduler / lifecycle 入口
业务域 -> client / ai / common
```

- 业务域之间不得直接 import，跨域生命周期由 `automation` 编排。
- `client`、`ai`、`common` 不得反向依赖业务域或 `automation`。
- `domain` 不依赖 `application`、`interfaces`、`infrastructure`。已有两个模型对 provider DTO 的历史依赖由架构测试精确列举，不得新增。
- `application/port` 不依赖接口入口或基础设施实现。Broker、订单存储、资金状态、行情缓存和审计等现有接口放在这里。
- 非接口层不得反向依赖本域 HTTP 或定时入口。`automation` 登记各域 scheduler 是跨域编排的明确职责。
- Web 不直接访问 Mapper 或暴露持久化 Row。请求路径、返回 JSON、配置键和表结构不因目录调整而变化。

这是统一的目录与职责边界，**尚未把全部历史代码改造成严格的端口适配架构**：部分 application 服务仍使用具体 Repository、行情适配器和配置对象；配置驱动的策略、风控上下文留在 `application`，不伪装成纯领域规则。只有需要可替换能力时再增加 port，不在本次结构调整中改写资金流程。

## 3. 类型应该放在哪里

| 内容 | 位置 | 示例 |
| --- | --- | --- |
| HTTP 接口、SSE、HTTP 专用请求 | `interfaces/web` | `TradingController`、`TradingCandleStream` |
| 定时触发 | `interfaces/scheduler` | `TradingScheduler` |
| 用例服务、事务编排 | `application/<能力>` | `application/order/OrderSettlementService` |
| 策略评估及配置驱动的风控 | `application/strategy`、`application/risk` | `TradingStrategyEngine`、`RiskControlService` |
| 出站契约 | `application/port` | `TradingBroker`、`TradingFinancialStateStore` |
| 状态、业务枚举 | `domain/model` 或领域能力包 | `ExecutionMode`、`domain/order/OrderStatus` |
| 纯规则 | `domain/order`、`domain/rule` 等 | `OrderStateMachine`、`TextGameRuleEngine` |
| 业务异常 | `domain/exception` | `TextGameConflictException` |
| 回测结果 | `domain/backtest` | `BacktestRun`、`BacktestTrade`、`FillPriceSource` |
| Broker 实现 | `infrastructure/broker` | `OkxLiveBroker`、`PaperBroker`、`BacktestBroker` |
| 数据库、文件与缓存实现 | `infrastructure/persistence` | `MyBatisTradingOrderRepository`、`RedisHotMarketDataCache` |
| 外部行情获取 | `infrastructure/market` | `OkxMarketDataWebSocketFeed`、`HistoricalCandleService` |
| 技术事件契约与信封 | `application/event`、`application/port` | `TradingEvent`、`TradingEventPublisher` |
| 队列与事件处理器 | `infrastructure/event` | `BoundedTradingEventBus` |
| Prompt、解析与业务校验 | `application/decision` | `AiStoryResponseParser` |
| 供应商 HTTP/WS、签名、协议 DTO | `client/<provider>` | `client/okx` |

不创建全局 `enums`、`dto`、`service` 或 `utils`。纯数据按业务归属放置，而不是只按 Java 语法分类。`MarketplaceViews` 是依赖 Row 的包级转换助手，留在 `application/service`；HTTP 专用鉴权异常可以留在 `interfaces/web`。

## 4. Trading 的主要调用链

### 任务与策略

```text
automation/application/task/AutomationTaskManager
  -> trading/interfaces/scheduler/TradingScheduler
    -> trading/application/strategy/TradingStrategyEngine
      -> 当前激活策略
      -> application/port/TradingBroker
        <- infrastructure/broker/TradingBrokerRouter
          -> PaperBroker / OkxLiveBroker
```

`AutomationTaskRegistrar` 只登记任务；`ApplicationReadyEvent + auto-start` 或任务 API 才会启动循环。同一任务内多个循环共用互斥锁，每轮结束后按 fixed delay 重新调度。交易多实例领导租约由 `application/runtime/TradingLeadershipService` 管理；真实下单前再次校验领导权。

### 真实资金闭环

真实提交前先建立幂等订单账本并取得提交所有权；订单接受后的即时查询与后台 `application/order/OrderReconciliationService` 共用结算服务：

```text
OKX order snapshot
  -> application/order/OrderSettlementService (@Transactional)
    -> OrderLifecycleService -> 订单 + 状态历史
    -> 累计成交 checkpoint -> okx_order_fill_ledger
    -> 仓位与成本 -> okx_position_state
    -> 已执行动作的风险状态 -> okx_risk_state
```

累计成交只应用相对 checkpoint 的差量，避免重复查询、崩溃重放和部分成交刷新重复加仓。LIVE 与 PAPER 使用不同 `account_scope`。幂等键、确定性 `clOrdId`、乐观锁、审计、事务边界均保持原语义；对账不得重新提交订单。

MySQL 是资金状态的权威来源。旧 JSON 的仓位、成本和风险仅在目标数据库行为空时兼容初始化；之后 JSON 只保存策略选择、画像和有界决策记忆。

资金级停止先提交 `HALTED`，再撤销挂单和设置 cancel-all-after。每次真实提交在取得提交所有权后再次读取停止门；恢复要求期望 revision、确认词、本地无待对账订单且交易所无挂单。

### 行情事件管道

```text
REST / WebSocket producers
  -> application/port/TradingEventPublisher
    <- infrastructure/event/BoundedTradingEventBus
      -> 有界队列 -> 隔离的 handlers -> MySQL / Redis
```

信封和载荷在 `application/event`，因为它们描述包含 provider DTO 的技术接入数据；策略推导出的业务信号是 `domain/model/MarketSignal`。

- 消费者随应用启动，HTTP 回测和 REST 采集无需启动交易任务。任务生命周期控制 WebSocket 和领导租约；应用停止时先停止生产，再在超时内排空队列。
- 队列保持固定容量，保留 `DROP_OLDEST`、`DROP_LATEST`、有超时的 `BLOCK` 行为。
- handler 异常隔离，不向 WebSocket 回调传播数据库失败。
- Micrometer 继续报告队列深度、容量、发布结果、丢弃、延迟和处理耗时；状态 API 保持 `/api/trading/runtime/events`。
- 历史 K 线异步写入，但调用返回时合并本次 REST 数据与缓存，保留回测读取语义。

## 5. Weibo 分层示例

```text
weibo/
├─ interfaces/web/           # WeiboController、HTTP 专用鉴权异常
├─ application/
│  ├─ service/               # OAuth、账号查询、发布用例
│  └─ port/                  # token 与 OAuth state 存储接口
├─ domain/
│  ├─ model/                 # 账号、令牌、授权结果
│  └─ exception/             # 授权与发布失败
└─ infrastructure/
   ├─ persistence/           # MyBatis 实现、Mapper、Row
   └─ config/                # 客户端 Bean 装配
```

供应商协议仍位于 `client/weibo`。共享 AI provider 的选择仍位于 `client/config/AiClientConfiguration`；OKX Bean 在 `trading/infrastructure/config/OkxClientConfiguration` 装配。

## 6. 验证与资源约定

- 生产代码和测试包路径镜像，移动类型必须同步移动测试并运行 `clean test`。
- MyBatis XML 保留 `src/main/resources/mapper/<module>/`，同步更新 namespace/resultType/parameterType。数据库表和手工迁移脚本不因搬包而改变。
- `application.yml` 的配置键、默认开关保持不变；新库基线仍是 `db/ai_trade_mysql_schema.sql`，存量升级仍使用手工 `db/migration/`。
- `PackageArchitectureTest` 检查包与路径、四层入口、业务域隔离、共享模块依赖方向、Web 不引用持久化、内层不依赖接口入口、port 不依赖实现、领域类型不依赖外层，以及 Mapper XML 中类名可解析。
- 既有 provider DTO 技术债仅迁移全限定名，不增加豁免条目。结构规则不得通过放宽限制来掩盖错误归层。
- Java 全限定名改变，仓库外 Java 调用方需更新 import；HTTP 调用方和数据库无需因此迁移。
