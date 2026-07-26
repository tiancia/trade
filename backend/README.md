# Trade Backend

基于 Spring Boot 4 和 Java 21 的后端服务，包含 OKX 策略交易、Polymarket AI 决策、AI 小说生成、文字游戏、二手集市、微博发布以及后台任务编排。

如果是第一次接触项目，建议先看本页的模块导航，再进入 [项目文档导航](docs/README.md)。代码采用“业务域优先、域内分层”的模块化单体结构，不按 Controller、Service、Mapper 建立全局大目录。

## 快速启动

前置条件：

- JDK 21；
- 可连接的 MySQL 数据库；
- Redis（可选，仅用于热行情缓存；未部署时关闭对应开关）；
- 在 `backend/` 目录运行命令，确保状态文件、小说输出目录和 Python 脚本的相对路径正确。

以下命令从仓库根目录执行；如果终端已经位于 `backend/`，跳过第一行：

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

`.env.example` 是环境变量清单，不会被 Spring Boot 自动加载。请把所需变量配置到当前 Shell、IDE Run Configuration 或部署环境中。默认数据库账号为 `root`、密码为空；实际环境应显式设置 `SPRING_DATASOURCE_*`。

交易、Polymarket 和小说生成默认关闭。数据库、密钥和风险参数未确认前，不要开启自动任务或真实下单开关。

## 模块导航

| 模块 | 主要职责 | 首要入口 | HTTP / 启动方式 |
| --- | --- | --- | --- |
| `automation` | 注册、启动、停止和查看后台循环 | `AutomationTaskController`、`AutomationTaskRegistrar` | `/api/automation/tasks` |
| `trading` | OKX 行情、策略、风控、模拟/真实执行和回测 | `TradingController`、`TradingStrategyEngine` | `/api/trading`，任务 ID `trading` |
| `polymarket` | 市场筛选、AI 决策、下单校验和审计 | `AiPolymarketService` | 任务 ID `polymarket` |
| `story` | 热点采集、AI 分段生成和文件落盘 | `AiStoryService` | 任务 ID `story` |
| `textgame` | 剧情发布、会话推进和规则计算 | `TextGameController`、`TextGameAdminController` | `/api/text-game`；配置管理员令牌后才创建 `/admin` API |
| `marketplace` | 用户认证、商品、会话聊天和 OSS 上传凭证 | 三个 `Marketplace*Controller` | `/api/marketplace` |
| `weibo` | OAuth 授权、账号状态和微博发布 | `WeiboController` | `/api/weibo`；配置管理员令牌后才创建 |
| `client` | AI、OKX、Polymarket、微博等外部传输适配 | `AiClientConfiguration`、各 provider client | 由业务模块调用 |
| `ai` | 跨业务的 AI 解析失败审计契约与持久化 | `AiResponseParseErrorSink` | 内部能力 |
| `common` | 无业务归属的纯工具 | `TradingMath` | 内部能力 |

交易行情由 REST/WebSocket 生产者发布到模块级有界事件队列，再由独立消费者持久化；生产线程不直接访问数据库。LIVE 订单由后台对账循环持续收敛，订单状态、累计成交、仓位/成本、风险和资金停止状态以 MySQL 为权威。运行状态可通过 `GET /api/trading/runtime/events` 和 `GET /api/trading/runtime/status` 查看。驾驶舱通过 `GET /api/trading/market/candles` 读取 K 线快照，并通过 `/api/trading/market/candles/stream` 的 SSE 流接收增量；`PUT /api/trading/strategies/active` 会以乐观版本号持久化切换当前策略。回测的请求、成交和指标口径见 [Trading 回测说明](docs/TRADING_BACKTEST.md)。

## 目录速览

```text
backend/
├─ AGENTS.md                    # AI 编码助手的仓库约束
├─ CONTRIBUTING.md              # 开发、测试和评审规范
├─ docs/
│  ├─ README.md                 # 文档索引和事实来源边界
│  ├─ ARCHITECTURE.md           # 分层、依赖方向和扩展规则
│  ├─ MODULES.md                # 模块职责、入口、配置和数据归属
│  ├─ OPERATIONS.md             # 启停、观测、迁移和故障处理
│  ├─ TRADING_BACKTEST.md       # 回测 API、成交与指标口径
│  └─ adr/                      # 只追加的架构决策记录
├─ data/
│  └─ trading-state.example.json
├─ src/main/java/com/trade/
│  ├─ automation/              # 跨域后台任务编排
│  ├─ trading/                 # OKX 交易域
│  ├─ polymarket/              # Polymarket 决策域
│  ├─ story/                   # 小说生成域
│  ├─ textgame/                # 文字游戏域
│  ├─ marketplace/             # 二手集市域
│  ├─ weibo/                   # 微博域
│  ├─ client/                  # 外部系统传输适配
│  ├─ ai/                      # 共享 AI 基础能力
│  └─ common/                  # 业务无关的共享代码
├─ src/main/resources/
│  ├─ application.yml          # 运行配置与安全默认值
│  ├─ db/                      # 新库基线和手工迁移脚本
│  ├─ mapper/<domain>/         # 按所属业务域分组的 MyBatis XML
│  └─ textgame/stories/        # 内置剧情资源
└─ src/test/java/com/trade/    # 与生产包路径镜像的测试
```

详细的标准域目录、允许依赖和典型调用链见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

维护者应同时阅读 [贡献指南](CONTRIBUTING.md)；使用 AI 介入开发时，先让工具读取根目录 [AGENTS.md](AGENTS.md)。模块定位、运维和值守分别以 [模块目录](docs/MODULES.md) 和 [运维手册](docs/OPERATIONS.md) 为准。Prometheus、Grafana 和 trading 告警的本地搭建见 [Trading 可观测性](docs/OBSERVABILITY.md)。

## 配置与安全开关

后台能力通常有三层开关，含义不同：

1. `trade.automation.<task>.auto-start`：应用启动后是否自动启动循环；
2. `trade.trading.enabled`、`trade.polymarket.enabled`、`trade.story.enabled`：业务模块是否执行；
3. 真实资金开关：OKX 必须同时满足 `execution-mode=live` 和 `live-enabled=true`，Polymarket 必须设置 `execution.enabled=true`。

只打开自动启动并不等于允许真实下单；真实资金开关也不应在缺少风控参数、API 权限或地域检查时启用。

新的 `live` 资金域会以 `HALTED` 初始化。首次上线或迁移后应保持
`live-enabled=false`，完成一次成功对账，再通过受保护的 resume 流程显式放行。
当前 LIVE 资金结算仅开放 SPOT 市价单；衍生品保留只读恢复能力，但新单会安全阻断。

常用环境变量示例见 `.env.example`。密钥、令牌和运行时状态不得提交到 Git；`data/trading-state.json` 只保存非资金策略记忆，仓位、成本、风险和资金停止状态保存于 MySQL，仓库只保留示例文件。

`trade.trading.event-queue.full-policy` 支持 `drop-oldest`、`drop-latest` 和 `block`。实时行情默认使用 `drop-oldest` 保留更新数据；选择 `block` 时，生产者最多等待 `publish-timeout-ms`，适合不允许静默丢弃且上游能够承受阻塞的场景。

## 数据库

应用启动时会执行 `db/ai_trade_mysql_schema.sql`。该文件使用 `CREATE TABLE IF NOT EXISTS`，适合初始化新库，但不会自动把已有表升级到最新字段结构。

`db/migration/` 下的脚本是手工迁移，不使用 Flyway 或 Liquibase，也不会被 Spring 自动执行。升级已有数据库前请先备份，再根据当前表结构选择脚本；具体约定见 [迁移说明](src/main/resources/db/migration/README.md)。

## 测试

```powershell
.\mvnw.cmd clean test
```

单元测试和集成测试应放在与生产代码一致的包路径中。例如修改 `com.trade.weibo.application` 时，相应测试应位于 `src/test/java/com/trade/weibo/application/`。

默认测试套件不会真实下单；需要外部凭据的 OKX 集成测试会按条件跳过。
