# 后端架构说明

后端采用业务域优先的模块化单体。模块职责见 [模块目录](MODULES.md)，整体选择见 [ADR-0001](adr/0001-domain-first-modular-monolith.md)，域内四层结构见 [ADR-0002](adr/0002-four-layer-module-layout.md)。

## 1. 先定位业务域，再定位层和能力

业务域包括 `trading`、`polymarket`、`story`、`textgame`、`marketplace`、`weibo`。它们保留同一个 Spring Boot 进程、Maven 构建和数据库连接；没有拆成微服务或多个 Maven module。

业务域、`automation` 和共享能力模块 `ai`、`telegram` 只使用以下四种一级目录，按需创建：

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
业务域 -> client / ai / telegram / common
```

- 业务域之间不得直接 import，跨域生命周期由 `automation` 编排。
- `client`、`ai`、`telegram`、`common` 不得反向依赖业务域或 `automation`。
- `domain` 不依赖 `application`、`interfaces`、`infrastructure`。不依赖供应商 client；架构测试不再保留 provider DTO 豁免。
- `application/port` 不依赖接口入口或基础设施实现。Broker、订单存储、资金状态、行情缓存和审计等现有接口放在这里。
- 非接口层不得反向依赖本域 HTTP 或定时入口。`automation` 登记各域 scheduler 是跨域编排的明确职责。
- Web 不直接访问 Mapper 或暴露持久化 Row。请求路径、返回 JSON、配置键和表结构不因目录调整而变化。

目录分层不等于完成 DDD。判断职责的依据是变化原因，而不是类的长度：业务阈值、状态演进和不变量归 domain；数据加载、事务、调用顺序、审计及指标归 application；外部协议、持久化与框架装配归 infrastructure；HTTP 与定时入口归 interfaces。规则由配置驱动并不意味着必须放在 application：先把配置和 provider DTO 转成领域参数，再调用纯规则。

已收敛的边界：

- `RiskControlService` 加载/保存状态并记录指标；`domain/risk` 执行六类风控规则及交易日、亏损冷却、已成交动作的状态演进。`RiskInputs` 负责配置转换，`TradingMarketInputs` 负责账户与行情 DTO 到领域输入的转换。
- `OrderSizingService` 转换余额、品种限制和配置；`domain/order/OrderSizingRules` 执行数量上限、步长取整与最小量规则。
- `ThresholdEventStrategy` 负责注册及输入转换；`domain/strategy/ThresholdDecisionPolicy` 决定价量入场、反向波动与浮亏退出。
- `TradingScheduler` 只调用 `TradingTriggerService`。后者编排行情回退、领导权校验、扫描冷却和决策触发；这些是有状态的用例流程。
- Trading 用例通过 `TradingStateStore`、`TradingMarketSource`、`TradingMarketFeed` 和 `TradingEventPipeline` 使用状态、行情及事件管道。事件状态 HTTP 接口调用 `TradingEventStatusService`。
- Polymarket 市场期限、成交量、价差及流动性规则位于 `domain/rule`，采集与执行共同使用 `MarketEligibilityPolicy`。
- 集市商品可见性、卖家权限、价格及图片归属规则，以及会话参与者和消息内容规则位于 `domain/rule`；数据库查询、事务和唯一键冲突处理仍归应用用例。
- 文字游戏规则保留在 domain，Spring Bean 装配移到 `TextGameConfiguration`。

进一步按实际行为下沉的核心规则：

| 能力 | domain 决定什么 | application 编排什么 |
| --- | --- | --- |
| 订单与资金 | `OrderIdentity`、`OrderChange`、`OrderSettlementPolicy`、`PositionAccounting` 决定幂等身份、状态演进、手续费、累计成交差量和持仓成本；`FundSafetyPolicy` / `ReconciliationPolicy` 判断恢复条件与资金偏差 | CAS 重试、事务、账本更新、审计、HALTED 后撤单的调用顺序 |
| 执行 | `ExecutionEligibility` 校验动作与品种；`PaperFillPolicy` 计算模拟成交；`AccountValuation` 计算权益与开仓上限 | `LiveOrderExecutionService` / `PaperOrderExecutionService` 编排门禁、风控、预留、提交和结算 |
| 行情与回测 | `MarketSignalPolicy` 判断价量/浮亏触发；`SimulatedPortfolio` 管理模拟持仓、手续费、滑点和盈亏；`BacktestPolicy` / `BacktestStatistics` 校验参数和统计结果 | 历史加载、逐根推进、策略调用、异步任务和结果发布 |
| Polymarket | `PolymarketDecisionRules` 校验业务有效性；`PolymarketOrderPolicy` 决定执行阈值、花费上限、最小数量和取整 | 解析、执行开关、地域检查、下单和审计 |
| 故事 | `StoryDraftPolicy` 决定分节、篇幅预算、续写条件、连续性和伏笔回收、兜底草稿 | AI 调用与错误审计、逐节生成、文件保存 |
| 文字游戏 | `SessionProgression` 决定选择资格、效果/分支/历史顺序、阶段和版本冲突；`StoryPublicationPolicy` 校验发布条件 | 加载固定版本、事务更新、CAS、事件保存和视图转换 |
| 集市 | 商品/会话权限、内容、价格、图片归属；`MarketplaceAccountRules` / `MarketplaceImageRules` 校验账号和图片元数据 | 查询、事务、密码编码、登录令牌和单对象上传凭证申请 |

基础设施的 Broker 只负责接入转发；`OkxOrderGateway` 处理供应商响应、拒绝和只读查询重试。MyBatis 实现保留行锁、账本原子写入、行映射，金额计算复用领域规则。阿里云授权策略 JSON、签名与 STS 请求统一由 `infrastructure/oss` 实现。

并非所有 if/循环都应下沉：输入缺失、记录查询、外部调用失败、分页、事务/CAS 重试、任务取消、锁和资源释放属于用例或技术处理。Prompt、JSON 别名和格式容错留在 `application/decision`，交易概率/金额等业务约束调用 domain。Weibo OAuth/发布与 automation 主要是外部调用和生命周期编排，不为它们制造空领域服务。

仍保留的技术耦合有明确边界：textgame 的管理用例与 marketplace 的应用服务仍使用 Mapper/Row，部分用例使用配置类和 provider client；`ExchangeOrderGateway` 是 OKX 专用契约，继续使用现有协议 DTO；原始采集上下文 `TradingDecisionContext` 位于 `application/market`，Prompt 与审计信封 `AiDecisionAuditRecord` 位于 `application/decision`。领域规则只接收 `TradingMarketInputs` 转换后的既有 Facts；Polymarket 盘口通过不可变 `MarketDepthLevel` 复制供应商档位，保留 JSON 的 price/size 字符串精度。这些不进入新增纯领域规则，也不代表全仓已达到严格六边形架构。没有为简单 CRUD 新增机械转发仓储，不能把 Row 改名搬进 domain。

本次进一步收紧的边界：

- `TradingOrder` 不提供公开构造器或 setter，业务推进仅通过不可任意构造的 `OrderChange`，保留状态机、同状态成交刷新、CAS 版本和完成时间语义。持久化使用 `TradingOrderRow`；`restore(Snapshot)` 只用于数据库恢复，禁止应用层把恢复入口当成状态修改入口。
- `TradingPositionState` 是不可变快照，买入、卖出与对账通过领域方法返回下一版本；实时成交卖出仍严格禁止超出受管理持仓，普通卖出保留原来的归零行为。累计成交 checkpoint、行锁和持仓写入仍由持久化适配在同一事务中完成。
- `BacktestExecutorConfiguration` 拥有回测线程池及停机；`BacktestService` 注入名为 `backtestExecutor` 的 `Executor`，保留 2 个线程、32 个排队任务、拒绝策略和 5 秒停机等待，不新增配置项。
- `TextGameSessionService` 通过 `TextGameSessionStore` 加载与保存 `GameSession`，调用领域规则后在原事务中更新会话并写事件；`MyBatisTextGameSessionStore` 承担 Row、存档及事件 JSON 转换、CAS SQL 结果检查；`TextGameSessionViews` 只构建用例响应。旧存档字段、固定剧情版本、修订号和 HTTP JSON 不变。

## 3. 类型应该放在哪里

| 内容 | 位置 | 示例 |
| --- | --- | --- |
| HTTP 接口、SSE、HTTP 专用请求 | `interfaces/web` | `TradingController`、`TradingCandleStream` |
| 定时触发 | `interfaces/scheduler` | `TradingScheduler` |
| 用例服务、事务编排 | `application/<能力>` | `application/order/OrderSettlementService` |
| 策略注册、输入转换及风控编排 | `application/strategy`、`application/risk` | `TradingStrategyEngine`、`RiskControlService` |
| 出站契约 | `application/port` | `TradingBroker`、`TradingFinancialStateStore` |
| 状态、业务枚举 | `domain/model` 或领域能力包 | `ExecutionMode`、`domain/order/OrderStatus` |
| 纯规则 | `domain/order`、`domain/risk`、`domain/strategy`、`domain/rule` | `OrderSizingRules`、`RiskStateTransitions`、`ThresholdDecisionPolicy`、`TextGameRuleEngine` |
| 业务异常 | `domain/exception` | `TextGameConflictException` |
| 回测结果 | `domain/backtest` | `BacktestRun`、`BacktestTrade`、`FillPriceSource` |
| Broker 实现 | `infrastructure/broker` | `OkxLiveBroker`、`PaperBroker`、`BacktestBroker` |
| 数据库、文件与缓存实现 | `infrastructure/persistence` | `MyBatisTradingOrderRepository`、`RedisHotMarketDataCache` |
| 外部行情获取 | `infrastructure/market` | `OkxMarketDataWebSocketFeed`、`HistoricalCandleService` |
| 技术事件契约与信封 | `application/event`、`application/port` | `TradingEvent`、`TradingEventPublisher` |
| 队列与事件处理器 | `infrastructure/event` | `BoundedTradingEventBus` |
| Prompt、协议解析与格式校验 | `application/decision` | `AiStoryResponseParser` |
| 供应商 HTTP/WS、签名、协议 DTO | `client/<provider>` | `client/okx` |

不创建全局 `enums`、`dto`、`service` 或 `utils`。纯数据按业务归属放置，而不是只按 Java 语法分类。`MarketplaceViews` 是依赖 Row 的包级转换助手，留在 `application/service`；HTTP 专用鉴权异常可以留在 `interfaces/web`。

## 4. Trading 的主要调用链

### 任务与策略

```text
automation/application/task/AutomationTaskManager
  -> trading/interfaces/scheduler/TradingScheduler
    -> trading/application/runtime/TradingTriggerService
      -> trading/application/strategy/TradingStrategyEngine
        -> 当前激活策略 -> domain 策略规则
        -> application/port/TradingBroker
          <- infrastructure/broker/TradingBrokerRouter
            -> PaperBroker / OkxLiveBroker
              -> PaperOrderExecutionService / LiveOrderExecutionService
                -> domain policies + application ports
```

`AutomationTaskRegistrar` 只登记任务；`ApplicationReadyEvent + auto-start` 或任务 API 才会启动循环。同一任务内多个循环共用互斥锁，每轮结束后按 fixed delay 重新调度。交易多实例领导租约由 `application/runtime/TradingLeadershipService` 管理；真实下单前再次校验领导权。

### 真实资金闭环

真实提交前先建立幂等订单账本并取得提交所有权；订单接受后的即时查询与后台 `application/order/OrderReconciliationService` 共用结算服务：

```text
OKX order snapshot
  -> application/order/OrderSettlementService (@Transactional)
    -> OrderSettlementPolicy / OrderChange -> 结算计划与下一状态
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

热点审核工作流沿用相同四层：不可变 `WeiboPost` 聚合管理规则，应用服务通过 port 编排
RSS/Atom、AI、MyBatis 和发布。共享 `telegram` 与 `ai` 使用四层且禁止反向依赖业务域，
只定义通用审核请求/决策与投递，不拥有微博状态。参见 [ADR-0003](adr/0003-shared-human-review-capability.md)
和 [微博工作流](WEIBO_WORKFLOW.md)。

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
- domain 不允许引用 provider DTO；应用层采集与执行契约可以保留供应商协议，在调用纯规则前转换。结构规则不得通过放宽限制来掩盖错误归层。
- 新增边界检查禁止 domain 引入 Spring/MyBatis/SQL 框架，禁止 Trading 的 application/interfaces 直接依赖已抽象的四类状态/行情/事件实现。
- Java 全限定名改变，仓库外 Java 调用方需更新 import；HTTP 调用方和数据库无需因此迁移。订单/持仓不再提供 setter，直接使用这些 Java 类型的调用方需改用领域方法；MyBatis 订单映射已同步改为持久化 Row，无需数据库迁移。
