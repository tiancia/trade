# 模块目录

后端是一个 Spring Boot 模块化单体。所有模块共享同一个进程和数据库连接，但代码所有权、配置和依赖方向按业务域隔离。模块之间需要共享生命周期时由 `automation` 编排，不直接互相调用。

## 总览

| 模块 | 类型 | 主要入口 | 配置前缀 / 数据 |
| --- | --- | --- | --- |
| `automation` | 跨域编排 | `AutomationTaskController`、`AutomationTaskRegistrar`、`AutomationTaskManager` | `trade.automation.*`；不拥有业务表 |
| `trading` | 业务域 | `TradingController`、`TradingScheduler`、`TradingStrategyEngine` | `trade.trading.*`、`trade.okx.*`；OKX 行情、决策、订单、资金状态和回测表，本地策略记忆，Redis 热缓存 |
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

`AutomationTaskRegistrar` 把 trading、polymarket、story、weibo、x 的循环定义登记到 `AutomationTaskManager`。登记不等于运行：只有应用就绪后的 `auto-start` 或 `/api/automation/tasks/{taskId}/start` 才会调用 `start()`。

两者位于 `automation/application/task`；HTTP 入口在 `interfaces/web`，任务定义和快照在 `domain/model`，调度器装配在 `infrastructure/config`。

同一任务内的多个循环共用互斥锁，避免同时修改同一外部账户或本地状态；每次执行完成后按 fixed delay 自调度。业务逻辑必须留在所属域，不能迁入 automation。

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
│  ├─ event/              # 技术事件信封、载荷与状态
│  └─ port/               # Broker、存储、缓存、事件与审计契约
├─ domain/
│  ├─ model/              # 决策、策略记忆、运行快照、业务枚举
│  ├─ order/              # 幂等身份、状态演进、结算、数量与成本规则
│  ├─ risk/               # 风控规则、状态演进、评估与资金状态
│  ├─ strategy/           # 纯价量阈值与仓位退出规则
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

真实订单可靠性依赖持久化幂等键、确定性 `clOrdId`、状态机、乐观锁、状态历史和累计成交账本。仓位、成本、风险状态与资金级停止状态以 MySQL 为权威；`data/trading-state.json` 只保存策略选择、策略画像和有界决策记忆。事件管道依赖固定容量、显式队满策略、handler 异常隔离、指标与优雅排空。修改这些路径前应先读对应测试。

### polymarket 与 story

两个域共享 `client.ai.AiTextClient`，但 Prompt、解析器、业务校验和审计仍归各自领域。Polymarket 的真实执行还必须经过 execution 开关、市场约束和 geoblock 检查；story 的输出由 `StoryFileRepository` 写入配置目录。

用例在 `application/service`，Prompt 与解析在 `application/decision`，模型在 `domain/model`，触发器在 `interfaces/scheduler`。Polymarket 的订单编排在 `application/execution`，Python 下单及地域检查实现在 `infrastructure/broker`，行情在 `infrastructure/market`；story 的热点采集在 `infrastructure/trend`，文件输出在 `infrastructure/persistence`。

Polymarket 市场资格规则在 `domain/rule/PolymarketMarketFilters`，参数由 `MarketEligibilityPolicy` 表达，不依赖配置绑定类；行情采集和下单校验共同复用。

`PolymarketOrderPolicy` 负责执行阈值和数量计算，解析后的业务有效性由 `PolymarketDecisionRules` 校验。故事的分节、篇幅、续写、连续性和伏笔回收位于 `domain/rule/StoryDraftPolicy`，AI 调用循环留在应用服务。

共享 AI 审计接口在 `ai/application/port`，记录在 `ai/domain/model`，MyBatis 实现在 `ai/infrastructure/persistence`。

### textgame 与 marketplace

这两个 HTTP 业务域使用 `interfaces/web`、`application/service`、`domain/model`、`domain/exception`、`infrastructure/persistence` 和 `infrastructure/config`。文字游戏的纯规则额外放在 `domain/rule`；集市的 OSS 实现放在 `infrastructure/oss`，契约放在 `application/port`。文字游戏会话用例通过 `application/port/TextGameSessionStore` 访问持久化；管理用例和集市部分 application service 仍直接依赖 Mapper 或 Row，后续随具体需求渐进收敛。

文字游戏和集市的共享业务异常均位于各自的 `domain/exception`。集市商品和会话规则在 `domain/rule`，应用服务仍负责查询与事务；`MarketplaceViews` 保留为 `application/service` 内部的 Row 转换助手。文字游戏规则的 Spring 装配位于 `TextGameConfiguration`，规则类不依赖 Spring。

`GameSession` 承载类型化会话状态并调用 `SessionProgression` 进行选择和阶段推进；`MyBatisTextGameSessionStore` 隔离 Row 与旧存档 JSON，`TextGameSessionViews` 负责响应视图，会话更新与事件写入仍在应用事务中。`StoryPublicationPolicy` 负责发布条件；账号和图片元数据规则分别由 `MarketplaceAccountRules` 和 `MarketplaceImageRules` 承担。OSS 单对象授权策略的 JSON 和签名属于 `infrastructure/oss`。

### weibo

Weibo 是 application port 模式的参考实现：`application/service` 依赖 `application/port`，MyBatis adapter 位于 `infrastructure/persistence`，供应商 HTTP 协议位于 `client/weibo`。新增相似 OAuth 或发布模块时优先参考这一依赖方向。

授权和发布用例异常位于 `weibo/domain/exception`；仅用于 HTTP 管理员鉴权的 `WeiboUnauthorizedException` 留在 `interfaces/web`。

热点评论由 `WeiboPostService` 编排；`domain/model/WeiboPost` 管理正文版本、审核和发布状态。
RSS/Atom 源在 `infrastructure/trend`，AI Prompt 在 `application/decision`，存储经 application port。
审核契约位于 `weibo/application/port/HumanReviewGateway`，请求与决策位于 `weibo/domain/model`。
`infrastructure/review/TelegramHumanReviewGateway` 发送按钮并认证轮询回调，投递去重、决定、游标和租约由 MyBatis 持久化；
未启用时使用 `UnavailableHumanReviewGateway`，不会批准。任务包含生成、审核与发布循环；配置默认关闭。
详见 [微博工作流](WEIBO_WORKFLOW.md)。

### x

`XPostService` 按配置方向、语言、语气与字数范围生成原创文本草稿，保存规则快照，
经 `TelegramXReviewGateway` 认证审核后调用 `XApiPostPublisher` 发布。
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

数据库模块索引和启动、升级边界见 [数据库目录](../src/main/resources/db/README.md)。模块入口、配置前缀或资源所有权变化时，必须同步更新本页、对应 `package-info.java` 和架构测试。
