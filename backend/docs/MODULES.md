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
| `weibo` | 业务域 | `WeiboController`、`Weibo*Service` | `trade.weibo.*`；OAuth state 和账号 token 表 |
| `client` | 共享出站适配 | `AiClientConfiguration`、各 provider client | `trade.ai.client`、`trade.gemini`、`trade.okx` 等；不拥有业务数据 |
| `ai` | 共享基础能力 | `AiResponseParseErrorSink` | AI 解析失败审计表 |
| `common` | 共享纯代码 | `TradingMath` | 无配置、无 I/O、无 Spring 生命周期 |

生产代码位于 `src/main/java/com/trade/<module>`；测试在 `src/test/java/com/trade/<module>` 镜像对应包路径。每个一级模块的 `package-info.java` 是离代码最近的职责说明。

## 关键模块

### automation

`AutomationTaskRegistrar` 把 trading、polymarket、story 的循环定义登记到 `AutomationTaskManager`。登记不等于运行：只有应用就绪后的 `auto-start` 或 `/api/automation/tasks/{taskId}/start` 才会调用 `start()`。

同一任务内的多个循环共用互斥锁，避免同时修改同一外部账户或本地状态；每次执行完成后按 fixed delay 自调度。业务逻辑必须留在所属域，不能迁入 automation。

### trading

Trading 是最复杂的业务域，内部按职责继续拆分：

```text
trading/
├─ web/           # HTTP 与运行状态入口
├─ application/   # 策略用例和行情运行时编排
├─ model/         # 决策、行情、订单输入等稳定值
├─ strategy/      # 可注册策略与信号规则
├─ risk/          # 资金与账户风险硬门禁
├─ execution/     # paper/live/backtest broker 与数量计算
├─ order/         # 幂等键、订单账本、状态机和 repository 契约
├─ market/        # REST/WS 行情采集、历史 K 线与事件检测
├─ event/         # 有界事件总线、handler、背压与指标
├─ backtest/      # 回测编排、交易与权益结果
├─ persistence/   # MyBatis、文件状态和 Redis adapter
├─ scheduler/     # automation 触发的薄入口
└─ config/        # Trading/OKX 属性与 Bean 组合
```

主要调用链：

```text
AutomationTaskManager
  -> TradingScheduler
    -> TradingStrategyEngine
      -> strategy -> risk/sizing -> broker
        -> paper state 或 OkxLiveBroker -> OrderLifecycleService -> OKX

  -> reconciliation loop
    -> OrderReconciliationService -> OKX order/account query
      -> OrderSettlementService -> order + fill ledger + position/risk transaction

REST / WebSocket market data
  -> TradingEventPublisher -> bounded queue
    -> isolated handlers -> MySQL / Redis
```

真实订单可靠性依赖持久化幂等键、确定性 `clOrdId`、状态机、乐观锁、状态历史和累计成交账本。仓位、成本、风险状态与资金级停止状态以 MySQL 为权威；`data/trading-state.json` 只保存策略选择、策略画像和有界决策记忆。事件管道依赖固定容量、显式队满策略、handler 异常隔离、指标与优雅排空。修改这些路径前应先读对应测试。

### polymarket 与 story

两个域共享 `client.ai.AiTextClient`，但 Prompt、解析器、业务校验和审计仍归各自领域。Polymarket 的真实执行还必须经过 execution 开关、市场约束和 geoblock 检查；story 的输出由 `StoryFileRepository` 写入配置目录。

### textgame 与 marketplace

这两个 HTTP 业务域已有清晰的 web/application/model/persistence 分包，但部分 application service 仍直接依赖 Mapper 或 Row。这是渐进重构区：新增可替换存储能力时优先引入 application port 和数据库无关模型，不要为了统一命名一次性改写所有接口。

### weibo

Weibo 是 application port 模式的参考实现：application service 依赖 `application/port`，MyBatis adapter 位于 persistence，供应商 HTTP 协议位于 `client/weibo`。新增相似 OAuth 或发布模块时优先参考这一依赖方向。

## Resources 所有权

| 路径 | 所有者 / 用途 |
| --- | --- |
| `application.yml` | 全局组合点；配置类仍归各自模块 |
| `db/ai_trade_mysql_schema.sql` | 所有数据库模块的新库完整基线 |
| `db/migration/` | 存量数据库手工升级记录 |
| `mapper/<domain>/` | 对应业务域的 MyBatis XML |
| `textgame/stories/` | textgame 内置故事定义 |
| `data/trading-state.example.json` | trading 非资金策略记忆格式示例；仓位/成本/风险不在此保存 |

模块入口、配置前缀或资源所有权变化时，必须同步更新本页、对应 `package-info.java` 和架构测试。
