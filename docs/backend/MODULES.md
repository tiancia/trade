# 模块目录

后端是一个 Spring Boot 模块化单体。所有模块共享同一个进程和数据库连接，但代码所有权、配置和依赖方向按业务域隔离。模块之间需要共享生命周期时由 `automation` 编排，不直接互相调用。

## 总览

| 模块 | 类型 | 主要入口 | 配置前缀 / 数据 |
| --- | --- | --- | --- |
| `automation` | 跨域编排 | `AutomationTaskController`、`AutomationTaskRegistrar`、`AutomationTaskManager` | `trade.automation.*`；不拥有业务表 |
| `trading` | 业务域 | `TradingController`、`TradingScheduler`、`TradingStrategyEngine` | `trade.trading.*`、`trade.okx.*`；MySQL 行情、订单与金融状态，本地策略/近期决策记忆，Redis 热缓存；当前回测运行结果保存在进程内存 |
| `polymarket` | 业务域 | `AiPolymarketScheduler`、`AiPolymarketService` | `trade.polymarket.*`；决策审计表 |
| `story` | 业务域 | `AiStoryScheduler`、`AiStoryService` | `trade.story.*`；配置目录下的生成文件 |
| `textgame` | 业务域 | `TextGameController`、`TextGameAdminController` | `trade.text-game.*`；故事、版本、会话和事件表 |
| `marketplace` | 业务域 | `Marketplace*Controller`、`Marketplace*Service` | `trade.marketplace.*`；用户、商品、会话和消息表 |
| `weibo` | 业务域 | `WeiboController`、`WeiboPostService`、`WeiboScheduler` | `trade.weibo.*`；OAuth、草稿、审核投递/轮询、历史、发布尝试和账号配额锁 |
| `x` | 业务域 | `XPostController`、`XPostService`、`XScheduler` | `trade.x.*`；冻结内容规则、草稿、审核、历史、尝试和账号配额锁 |
| `client` | 共享出站适配 | `AiClientConfiguration`、各 provider client | `trade.ai.client`、`trade.gemini`、`trade.okx`、`trade.telegram` 等；不拥有业务数据 |
| `ai` | 共享基础能力 | `AiResponseParseErrorSink` | AI 解析失败审计表 |
| `common` | 共享纯代码 | `TradingMath` | 无配置、无 I/O、无 Spring 生命周期 |

生产代码位于 `src/main/java/com/trade/<module>`；测试在 `src/test/java/com/trade/<module>` 镜像对应包路径。每个一级模块的 `package-info.java` 是离代码最近的职责说明。

业务域先分为 `interfaces`、`application`、`domain`、`infrastructure`，再按能力细分。下表中的类名可从这些目录定位；完整职责规则见 [架构说明](ARCHITECTURE.md)。

## 关键模块

### automation

`AutomationTaskRegistrar` 把 trading、trading-ai、polymarket、story、weibo、x 的循环定义登记到 `AutomationTaskManager`。登记不等于运行：只有应用就绪后的 `auto-start` 或 `/api/automation/tasks/{taskId}/start` 才会调用 `start()`。

两者位于 `automation/application/task`；HTTP 入口在 `interfaces/web`，任务定义和快照在 `domain/model`，调度器装配在 `infrastructure/config`。

普通业务任务使用 `automationTaskScheduler` 的四个线程。交易任务和数据库租约心跳使用独立的 `tradingTaskScheduler` 两个线程，避免其他域的同步 AI 请求占满调度池后延迟交易和租约续约；交易循环仍共用同一任务互斥锁。

同一任务内的多个循环共用互斥锁，避免同时修改同一外部账户或本地状态；每次执行完成后按 fixed delay 自调度。业务逻辑必须留在所属域，不能迁入 automation。

Story、Weibo、X 的调度入口会把内部已落库的生成、审核投递和发布失败反馈给 manager；批量执行仍隔离单条失败，但整轮 `lastRunSuccessful=false`。Story 的降级产物保留并标记 `degraded`，该轮同样报失败。禁用、配额或幂等预留未获准、无待处理项属于正常跳过；互斥锁忙只计 skipped，不覆盖上次运行结果。所有已登记循环都提供按 task/loop 标记的运行计数、耗时和最近结果 Micrometer 指标。

### trading

Trading 是最复杂的业务域，先按四层组织，再按业务与技术能力拆分：

```text
trading/
├─ interfaces/
│  ├─ web/                # HTTP、SSE、请求转换
│  └─ scheduler/          # 定时触发
├─ application/
│  ├─ strategy/           # 策略选择、注册和领域输入转换
│  ├─ order/              # 幂等、订单生命周期、结算与对账
│  ├─ risk/               # 风控编排、资金停止与恢复
│  ├─ execution/          # 下单编排、门禁与数量规则输入转换
│  ├─ backtest/           # 回测编排
│  ├─ runtime/            # 行情生命周期、领导租约、触发用例
│  ├─ market/             # 行情查询、采集上下文、领域输入转换与信号检测
│  ├─ decision/           # Prompt、解析与审计信封
│  └─ event/              # 技术事件信封、载荷与状态
├─ port/                  # Broker、存储、缓存、事件与审计契约
├─ domain/
│  ├─ model/              # 决策、策略记忆、运行快照、业务枚举
│  ├─ order/              # 幂等身份、状态演进、结算、数量与成本规则
│  ├─ risk/               # 风控规则、状态演进、评估与资金状态
│  ├─ strategy/           # 纯价量阈值、技术指标、15m日内策略与仓位退出规则
│  └─ backtest/           # 回测参数、模拟持仓、盈亏统计与结果
└─ infrastructure/
   ├─ broker/             # Broker 接入、OKX 协议与查询重试
   ├─ persistence/        # MyBatis、文件状态与 Redis
   ├─ market/             # REST/WS 接入与历史行情
   ├─ event/              # 有界总线与处理器
   └─ config/             # 属性绑定与 Bean 装配
```

从 `application/strategy/TradingStrategyEngine` 阅读决策，从 `application/order/OrderReconciliationService` 阅读对账，从 `application/runtime` 阅读生命周期。数据定义从 `domain` 查找，外部实现从 `infrastructure` 查找。

主要调用链：

```text
AutomationTaskManager
  -> TradingScheduler -> TradingTriggerService
    -> TradingStrategyEngine
      -> strategy -> risk/sizing -> broker
        -> PaperBroker / OkxLiveBroker -> application/execution 用例
          -> domain 规则 + OrderLifecycleService + ExchangeOrderGateway

  -> reconciliation loop
    -> OrderReconciliationService -> OKX order/account query
      -> OrderSettlementService -> order + fill ledger + position/risk transaction

  -> database leadership heartbeat
    -> one leader runs decision/event/reconciliation loops
      -> LiveOrderExecutionService revalidates leadership immediately before placeOrder

REST / WebSocket market data
  -> TradingEventPublisher -> bounded queue
    -> isolated handlers -> MySQL / Redis
```

订单对象不暴露 setter，状态推进经由 `OrderChange`；MyBatis 使用 `TradingOrderRow` 恢复不可变订单。持仓快照通过领域买入、卖出和对账方法演进。回测线程池由 `BacktestExecutorConfiguration` 装配并关闭，应用用例只提交任务。

回测经 `port/HistoricalCandleSource` 加载历史数据，默认适配为 `HistoricalCandleService`。实时引擎与 Broker 只执行 PAPER/LIVE；BACKTEST 配置不会启动历史回放或降级成实时模拟成交。活动策略切换与资金操作由部署网关及网络隔离限制访问，无需单独的应用令牌。

真实订单可靠性依赖持久化幂等键、确定性 `clOrdId`、状态机、乐观锁、状态历史和累计成交账本。仓位、成本、风险状态与资金级停止状态以 MySQL 为权威；`data/trading-state.json` 只保存策略选择、策略画像和有界决策记忆。事件管道依赖固定容量、显式队满策略、handler 异常隔离、指标与优雅排空。修改这些路径前应先读对应测试。

含糊的下单确认保持 `SUBMIT_UNKNOWN` 并阻断后续新单。结算校验累计成交量、均价及单调性；累计成交量相等或增加但净数量或成本倒退时，结算事务失败并保留旧账本，订单继续待对账。有未决订单时暂缓账户余额核对，现货账户余额不替代成交账本记仓；余额响应必须有账户和明细，缺失数据不当作零余额。LIVE 恢复必须收到明确的挂单列表，缺失列表不当作没有挂单。紧急停止的定时器与各笔撤单独立尽力执行，错误保留供人工处理。

页面参数编辑经 `StrategyConfigurationController → TradingStrategyRegistry` 保存 MySQL 当前配置及不可变版本历史；新决策冻结所用配置版本。`application/paper` 编排独立的 PAPER 初始资金、批次、成交与估值账本，`domain/paper` 负责现金、成本和盈亏演进。具体口径与接口见 [策略参数版本与模拟资金](TRADING_SIMULATION.md)。

`TradingAiScheduler → TradingAiAdvisor` 由独立的 `trading-ai` 任务评估公开行情，复用共享 AI 客户端，结果存入 MySQL 审计表。PAPER 空仓时可受限切换已有策略或调整信号参数；完整校验、配置行锁及模拟会话版本保护人工修改和在途成交。逻辑修改建议使用独立 Telegram Bot，无 X 审核轮询。配置、通知状态与回退步骤见 [Trading AI 策略顾问](TRADING_AI.md)。

策略输入显式区分1m、5m、15m。`intraday-technical` 提供趋势回踩、放量突破和震荡回归三种15m现货多头候选，复用纯领域指标；每分钟事件扫描独立评估确认收盘信号，订单及PAPER账本保存每个方向的稳定信号身份。新增实例不自动切换当前策略，不改变LIVE门禁；收益与频率需要独立回测、样本外及PAPER验证。

固定价格止损止盈只依赖最新有效确认收盘价，指标暖机故障不阻止该保护；产品精度以下的受管理零碎资产保留账本。`lastTradeTime` 随LIVE累计成交和PAPER成交事务持久化并保持单调，不依赖风险计数开关。历史回测校验完整、连续且对齐的半开请求区间，适配OKX排他分页边界；运行状态返回实际标的和币种供驾驶舱使用。

### polymarket 与 story

两个域共享 `client.ai.AiTextClient`，但 Prompt、解析器、业务校验和审计仍归各自领域。Polymarket 的真实执行还必须经过 execution 开关、市场约束和 geoblock 检查；story 的输出由 `StoryFileRepository` 写入配置目录。

用例在 `application/service`，Prompt 与解析在 `application/decision`，模型在 `domain/model`，触发器在 `interfaces/scheduler`。Polymarket 的订单编排在 `application/execution`，Python 下单及地域检查实现在 `infrastructure/broker`，行情在 `infrastructure/market`；story 的热点采集在 `infrastructure/trend`，文件输出在 `infrastructure/persistence`。

Polymarket 市场资格规则在 `domain/rule/PolymarketMarketFilters`，参数由 `MarketEligibilityPolicy` 表达，不依赖配置绑定类；行情采集和下单校验共同复用。

`PolymarketOrderPolicy` 负责执行阈值和数量计算，解析后的业务有效性由 `PolymarketDecisionRules` 校验。故事的分节、篇幅、续写、连续性和伏笔回收位于 `domain/rule/StoryDraftPolicy`，AI 调用循环留在应用服务。

Polymarket Python 桥接使用请求冻结的订单类型，不允许 SDK 兼容分支丢弃订单或签名类型；不完整的 API 凭据直接拒绝。Java 子进程的 stdin、运行和输出排空共用超时，每个输出流最多接收 1 MiB，失败异常不包含原始 SDK 输出。凭据辅助工具需显式选择联网及显示秘密，详见 [工具说明](../../tools/polymarket/README.md)。

Polymarket LIVE 决策在开始采集和 AI 调用前必须创建持久化审计记录；BUY 在执行前还须以独立事务预留 `polymarket_execution_guard` 的单账户门禁。超时、确认不完整、进程退出或执行审计失败都会保留未决预留，阻断同数据库下所有实例的后续真实下单，重启和等待均不自动释放。正常结果及审计均提交后才释放，未决恢复按[运维手册](OPERATIONS.md)人工核实。子进程正常退出后仍校验 CLOB 的接受标志、错误、订单 ID 和状态；`PLACED` 表示接受，不代表成交。采集、AI 或执行异常在保存审计后传播到任务管理器，计为失败；禁用、互斥锁忙和没有候选市场仍属于正常跳过。

共享 AI 审计接口在 `ai/port`，记录在 `ai/domain/model`，MyBatis 实现在 `ai/infrastructure/persistence`。

### textgame 与 marketplace

这两个 HTTP 业务域使用 `interfaces/web`、`application/service`、`domain/model`、`domain/exception`、`infrastructure/persistence` 和 `infrastructure/config`。文字游戏的纯规则额外放在 `domain/rule`；集市的 OSS 实现放在 `infrastructure/oss`，契约放在 `port`。文字游戏会话用例通过 `port/TextGameSessionStore` 访问持久化；管理用例和集市部分 application service 仍直接依赖 Mapper 或 Row，后续随具体需求渐进收敛。

文字游戏和集市的共享业务异常均位于各自的 `domain/exception`。集市商品和会话规则在 `domain/rule`，应用服务仍负责查询与事务；`MarketplaceViews` 保留为 `application/service` 内部的 Row 转换助手。文字游戏规则的 Spring 装配位于 `TextGameConfiguration`，规则类不依赖 Spring。

`GameSession` 承载类型化会话状态并调用 `SessionProgression` 进行选择和阶段推进；`MyBatisTextGameSessionStore` 隔离 Row 与旧存档 JSON，`TextGameSessionViews` 负责响应视图，会话更新与事件写入仍在应用事务中。`StoryPublicationPolicy` 负责发布条件；账号和图片元数据规则分别由 `MarketplaceAccountRules` 和 `MarketplaceImageRules` 承担。OSS 单对象授权策略的 JSON 和签名属于 `infrastructure/oss`。

### weibo

Weibo 是 application port 模式的参考实现：`application/service` 依赖 `port`，MyBatis adapter 位于 `infrastructure/persistence`，供应商 HTTP 协议位于 `client/weibo`。新增相似 OAuth 或发布模块时优先参考这一依赖方向。

授权和发布用例异常位于 `weibo/domain/exception`；仅用于 HTTP 管理员鉴权的 `WeiboUnauthorizedException` 留在 `interfaces/web`。

热点评论由 `WeiboPostService` 编排；`domain/model/WeiboPost` 管理正文版本、审核和发布状态。
RSS/Atom 源在 `infrastructure/trend`，AI Prompt 在 `application/decision`，存储经 application port。
审核契约位于 `weibo/port/HumanReviewGateway`，请求与决策位于 `weibo/domain/model`。
`infrastructure/review/TelegramHumanReviewGateway` 发送按钮并认证轮询回调，投递去重、决定、游标和租约由 MyBatis 持久化；
未启用时使用 `UnavailableHumanReviewGateway`，不会批准。任务包含生成、审核与发布循环；配置默认关闭。
详见 [微博工作流](WEIBO_WORKFLOW.md)。

### x

`XPostService` 按配置方向、语言、语气、格式与字数范围生成原创文本草稿，保存规则快照，
经 `TelegramXReviewGateway` 认证审核后调用 `XApiPostPublisher` 发布。
默认面向中文成年读者，生成聊天记录样式的原创虚构短对话，每条2～6句、一句一行且成对双引号，不附旁白或总结；
`XDraftPromptBuilder` 引导一次请求生成不同情绪与潜台词的多个候选，
`XAiDraftGenerator` 筛选格式、字数及近期重复，`XContentPolicy` 提供冻结格式校验和纯文本重复规则。旧草稿缺少格式字段时仍为自由正文。
同账号近期正文由应用用例读取，最终只持久化并审核一条正文，内容边界仍由人工确认。
`client/x` 负责 OAuth 1.0a 协议；业务状态、审计与六张表归 `x`。
普通帖子的官方加权字数由 `application/decision/XPostTextValidator` 校验，详情见 [X 工作流](X_WORKFLOW.md)。

### client

共享出站传输按外部服务或能力分为 `ai`、`okx`、`polymarket`、`weibo`、`telegram`、`x`。`client/ai` 保留统一的 `AiTextClient` 契约和 OpenAI 兼容实现；`client/ai/config/AiClientConfiguration` 负责 AI provider 选择，`client/ai/gemini` 包含 Gemini 客户端、配置属性和协议 DTO。`trade.ai.client`、`trade.gemini` 配置前缀保持不变。

`client/ai` 负责外部 AI 调用，顶层 `ai` 模块负责共享解析失败审计；各业务域继续拥有自己的 Prompt、解析和业务校验。

`client/telegram` 封装 Bot API 协议、DTO 与客户端装配，提供身份查询、文本/内联按钮发送、获取更新和回调应答。
`TelegramClientConfiguration` 绑定 `trade.telegram` 并装配共享 `TelegramApi`；微博与 X 各自拥有审核投递和认证。
属性默认关闭，创建客户端不访问 API。使用说明见 [Telegram Bot API](TELEGRAM_API.md)。

## Resources 所有权

| 路径 | 所有者 / 用途 |
| --- | --- |
| `application.yml` | 全局组合点；配置类仍归各自模块 |
| `db/schema/<module>/schema.sql` | 所属模块的完整结构；启动按显式配置列表执行 |
| `db/upgrade/<module>/` | 所属模块的存量数据库手工升级补丁；跨模块历史补丁归 `legacy/` |
| `mapper/<domain>/` | 对应业务域的 MyBatis XML |
| `textgame/stories/` | textgame 内置故事定义 |
| `data/trading-state.example.json` | trading 非资金策略记忆格式示例；仓位/成本/风险不在此保存 |

数据库模块索引和启动、升级边界见 [数据库目录](../../backend/src/main/resources/db/README.md)。模块入口、配置前缀或资源所有权变化时，必须同步更新本页、对应 `package-info.java` 和架构测试。
