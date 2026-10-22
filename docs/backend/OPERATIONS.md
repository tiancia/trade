# 运维手册

本手册负责后端应用的安全启动、任务控制、资金停止/恢复、观测和业务排障。全项目资料见 [文档总导航](../README.md)，三台机器与端口通信见 [全局上手指南](../PROJECT_GUIDE.md)，Ubuntu Docker、密钥、发布/回滚和备份见 [生产部署说明](../../deploy/README.md)。

下文 Maven 命令从 `backend/` 执行，直连 `127.0.0.1:8080` 的 API 示例面向宿主机运行的 Java 进程。Docker 私有部署通过 SSH 转发后的 `127.0.0.1:18080` 及网关认证访问，不能直接套用本地直连地址。

## 运行依赖

- JDK 21；
- MySQL，连接信息通过 `SPRING_DATASOURCE_*` 提供；
- Redis 仅用于 trading 热行情缓存。环境没有 Redis 时设置 `TRADE_TRADING_HOT_MARKET_CACHE_ENABLED=false`；
- Polymarket 真实执行额外依赖 Python 与仓库 `tools/polymarket/` 中的桥接脚本；
- 供应商 API 凭据只通过部署环境注入。

`.env.example` 是变量清单，Spring Boot 不会自动加载 `.env`。不要把真实值写回示例文件、启动脚本、日志或问题单。

## 仓库配置与运行环境

当前 `application.yml` 和 `.env.example` 按以下方案配置，环境变量仍可覆盖这些值：

| 能力 | 配置及行为 |
| --- | --- |
| OKX | `TRADE_AUTOMATION_TRADING_AUTO_START=true`、`TRADE_TRADING_ENABLED=true`、`TRADE_TRADING_EXECUTION_MODE=paper`、`TRADE_TRADING_LIVE_ENABLED=false`；自动运行策略，仅模拟成交 |
| X | auto-start、client、workflow、generation、Telegram review、publishing 和 live-publishing 均开启；只有经人工审核批准的版本可发布 |
| X 节奏 | `TRADE_X_GENERATION_FIXED_DELAY_MS=10800000`，每轮完成后等待三小时；首次延迟 30 秒；UTC 日生成 / 发布尝试额度各 8 次 |
| Polymarket | auto-start、业务、真实执行和代理均关闭 |
| 代理 | 宿主机直接运行 Java 时可使用 `127.0.0.1:13128` 的隧道入口；生产 Compose 将 OKX、Telegram、X 地址覆盖为 `egress:13128`，其容器负责连接德国 Squid |
| AI | DeepSeek 直连 `https://api.deepseek.com`，使用 `deepseek-flash`；供应商、型号、基础地址和密钥环境变量名可用 `TRADE_AI_PROVIDER`、`TRADE_AI_MODEL`、`TRADE_AI_BASE_URL`、`TRADE_AI_API_KEY_ENV_NAME` 覆盖 |

模型名在这里记录仓库配置，不代表供应商最新型号或账户实际可用权限。OKX 公共 / 业务 / 私有 WebSocket 地址使用默认 443 端口，可分别用 `TRADE_OKX_WS_PUBLIC_URL` / `TRADE_OKX_WS_BUSINESS_URL` / `TRADE_OKX_WS_PRIVATE_URL` 覆盖；Ticker 使用 `/public`，K 线使用 `/business`。`TRADE_TRADING_WEBSOCKET_ENABLED` 默认 `true`，trading 任务启动后分别建立一个 Ticker 连接和一个批量订阅 1m/5m/15m/1H/4H 的 K 线连接；真实下单仍受执行模式、实盘开关和风控门禁约束。

行情连接断开、异步发送失败或心跳超时后自动退避重连并恢复订阅，主动停止任务后不再重连。`TRADE_TRADING_WEBSOCKET_STALE_TIMEOUT_MS=30000` 控制进程内行情最长无更新时间。Redis 的 `TRADE_TRADING_HOT_MARKET_CACHE_CANDLE_TTL_MS=10800000` 只控制历史集合保留时间；另以 `TRADE_TRADING_HOT_MARKET_CACHE_CANDLE_FRESHNESS_MS=15000` 限制当前行情观测有效期，并检查最新 K 线所属周期。旧缓存没有观测标记、已经过期、跨周期或历史条数不足时，查询会回退 OKX REST。市场事件扫描也不会再仅凭旧 K 线数量足够而跳过刷新。

启用行情订阅时，若实际运行环境仍有 `TRADE_TRADING_WEBSOCKET_ENABLED=false`，需显式改为 `true` 并重新创建后端容器；仅替换镜像或修改 YAML 不会覆盖已有环境变量。这项配置变更无需数据库迁移，也无需删除 Redis 历史集合。驾驶舱与后端应部署兼容版本：SSE 连通与行情新鲜度分别判断，页面可见时每 15 秒校验快照；快照中的 `checkedAt` 表示后端校验时间，`asOf` 是最新 K 线开盘时间，增量沿用事件原始 `receivedAt`，不会把积压事件的发送时间当作新行情时间。历史数量不足或时间断档都会补拉，连续推送期间的 HTTP 回包只重放请求后的增量，不覆盖 REST 修正的已收盘 K 线。验收时检查各周期最新 K 线时间持续推进、Ticker/K 线订阅成功，以及断线恢复后恢复更新；`source=HOT_CACHE` 本身不表示故障。

当前 PAPER 使用独立的持久化模拟现金和持仓账本，行情及产品规则来自 OKX 公共接口，不读取真实账户数据，也不要求三个 OKX 私有凭据。LIVE 私有接口需另行配置和验收；模拟账本口径见 [策略参数版本与模拟资金](TRADING_SIMULATION.md)，完整部署与代理验收按生产部署说明执行。

开启 X 前，运行环境必须提供 `TRADE_X_TARGET_USER_ID`、`TRADE_X_TELEGRAM_CHAT_ID`、`TRADE_X_TELEGRAM_REVIEWER_USER_IDS`、Telegram Bot Token、DeepSeek API Key 和 X OAuth 1.0a 凭据。缺少必需的目标 / 审核配置会在启动校验时拒绝；发布权限、额度和实际网络连接需另行联调。启动后的发布扫描可能处理数据库中已有、未过期且审核仍有效的 `APPROVED` 草稿；`PENDING_REVIEW` 不发布，`UNKNOWN` 不自动重发。详细说明见 [X 工作流](X_WORKFLOW.md)。

Shell、IDE 或 systemd 已有环境变量会覆盖 YAML 回退值。启停自动任务前，应核对 `TRADE_AUTOMATION_TRADING_AUTO_START`、`TRADE_AUTOMATION_X_AUTO_START` 等实际值；修改后重启 Java 进程，仅修改 YAML 不会覆盖已有环境变量。systemd 部署可通过服务的 `EnvironmentFile=/etc/trade/trade.env` 注入运行配置；实际文件不进入仓库。SSH 隧道和 Java 服务需分别常驻，普通进程与容器中的 `127.0.0.1` 指向各自网络环境。

## 安全启动

首次启动或未知环境中，至少确认以下能力关闭：

```powershell
$env:TRADE_AUTOMATION_STORY_AUTO_START="false"
$env:TRADE_AUTOMATION_TRADING_AUTO_START="false"
$env:TRADE_AUTOMATION_POLYMARKET_AUTO_START="false"
$env:TRADE_TRADING_ENABLED="false"
$env:TRADE_TRADING_EXECUTION_MODE="paper"
$env:TRADE_TRADING_LIVE_ENABLED="false"
$env:TRADE_POLYMARKET_ENABLED="false"
$env:TRADE_POLYMARKET_EXECUTION_ENABLED="false"
$env:TRADE_STORY_ENABLED="false"
$env:TRADE_AUTOMATION_WEIBO_AUTO_START="false"
$env:TRADE_WEIBO_WORKFLOW_ENABLED="false"
$env:TRADE_WEIBO_GENERATION_ENABLED="false"
$env:TRADE_WEIBO_PUBLISHING_ENABLED="false"
$env:TRADE_WEIBO_LIVE_PUBLISHING_ENABLED="false"
$env:TRADE_AUTOMATION_X_AUTO_START="false"
$env:TRADE_X_CLIENT_ENABLED="false"
$env:TRADE_X_WORKFLOW_ENABLED="false"
$env:TRADE_X_GENERATION_ENABLED="false"
$env:TRADE_X_PUBLISHING_ENABLED="false"
$env:TRADE_X_LIVE_PUBLISHING_ENABLED="false"
$env:TRADE_X_TELEGRAM_REVIEW_ENABLED="false"
$env:TRADE_TELEGRAM_ENABLED="false"

.\mvnw.cmd spring-boot:run
```

OKX 真实下单还要求 `trade.trading.execution-mode=live` 与 `live-enabled=true` 同时满足。不要把“双开关”当作替代风控、账户权限和人工复核的安全措施。

`execution-mode=live` 还用于明确选择真实账户；只读对账、资金停止撤单和恢复前检查不依赖
`live-enabled=true`。因此事故恢复时可以保持 `live-enabled=false` 阻断一切新单，同时运行对账和撤单。

当前真实资金结算账本只开放 SPOT 市价单。SWAP/FUTURES/OPTION 仍可执行只读订单与仓位恢复，
但新的 LIVE 衍生品订单会 fail-closed；在合约乘数、保证金、强平和资金费率进入同一原子账本前不得解除该门禁。

## 启动后检查

1. `GET /actuator/health` 返回健康；
2. 日志中没有数据库初始化、Mapper 或配置绑定失败；
3. `GET /api/automation/tasks` 与所选启动配置一致；安全启动覆盖值下任务未运行，当前部署方案下 `trading` 和 `x` 自动运行；
4. `GET /api/trading/runtime/events` 的队列容量、深度和消费者状态合理；
5. `GET /api/trading/runtime/status` 中执行模式、live 开关、lastError 和最新决策符合预期；LIVE 放行前还需确认 `fundSafety.status=ACTIVE` 且对账无连续失败，HALTED 时先查明原因，不得直接改库；
6. 若启用 Redis，确认缓存写入成功且没有持续连接重试；
7. 再按任务逐个启用业务开关和 auto-start，避免一次打开多个外部副作用。

Actuator 当前暴露 health、info、metrics 和 prometheus。automation 启停 API 是运维控制面，应用内尚未提供统一运维鉴权；部署时必须由内网、网关或等价访问控制保护，不能直接暴露到公网。

## 后台任务操作

微博 `weibo` 任务包含生成、Telegram 审核与发布循环；默认关闭，真实发布需要多重门禁。
机器人将完整正文和审核按钮发到指定聊天，仅认证审核人能批准具体版本。配置、数据库升级和 UNKNOWN 排障见
[微博审核工作流](WEIBO_WORKFLOW.md)。

Telegram 协议和共享 Bean 在 `client/telegram`，审核行为分别由微博与 X 装配。启用微博前确认
`db/schema/weibo/schema.sql` 中的表已创建；旧表结构有差异时按[微博工作流](WEIBO_WORKFLOW.md)选择 `db/upgrade/weibo/` 补丁。安全注入 `TRADE_TELEGRAM_BOT_TOKEN`，
配置 `TRADE_WEIBO_TELEGRAM_CHAT_ID` 与 `TRADE_WEIBO_TELEGRAM_REVIEWER_USER_IDS`，再开启
`TRADE_TELEGRAM_ENABLED`、`TRADE_WEIBO_TELEGRAM_REVIEW_ENABLED` 和 workflow/generation 开关。
初次联调保持两个发布开关关闭；确认审核状态后，按[工作流说明](WEIBO_WORKFLOW.md)配置目标账号授权和发布门禁。
创建 Bean 不启动任务；实际聊天权限、消息发送和微博发布仍需独立联调。轮询模式不能与该 bot 已有 webhook 或另一套 consumer 共用。

X 的 `x` 任务也包含生成、审核、发布三个循环。启动 schema 会创建缺失的六张 X 表；已有表升级时检查 `db/upgrade/x/` 的适用补丁。
配置 `TRADE_X_CONTENT_*`、目标 X 用户 ID 与四个 OAuth 1.0a 凭据，再按
[X 工作流](X_WORKFLOW.md)联调。复用现有 Bot 时，先停止所有实例的微博审核并关闭
`TRADE_WEIBO_TELEGRAM_REVIEW_ENABLED`；应用会拒绝两套审核同时 enabled。
保留两项 X 发布开关关闭直到联调完成；X 外部协议见 [X 客户端](X_API.md)。

查询、启动和停止任务：

管理写请求（automation、trading、X、微博和文字游戏管理接口）统一要求 `X-Trade-CSRF: 1`。浏览器还必须来自同源或配置允许的 Origin；无 Origin 的命令行客户端也要带这个头。它是跨站请求防护标记，不替代网关认证或各业务已有鉴权。GET 查询和 OAuth 回调保持原有行为。

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/automation/tasks
Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} http://127.0.0.1:8080/api/automation/tasks/trading/start
Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} http://127.0.0.1:8080/api/automation/tasks/trading/stop
```

启动前检查所属域的 enabled 开关、凭据、风险参数、外部网络和数据库结构。`start` 是幂等的运行状态切换，不会自动把业务 enabled 开关改成 true。

automation `stop` 只阻止后续循环并停止域运行时，不代表资金级停止。真实资金异常应使用下节的持久化停止；该动作会先阻止新单，再尽力撤销挂单，但不会自动市价平仓。

## 多实例单写租约

同一 OKX 资金账户运行多个后端实例时，必须先确认租约表结构完整；手工建表或升级旧库使用
`db/upgrade/trading/migration_add_okx_trading_leader_lease.sql`，再在所有实例启用：

```powershell
$env:TRADE_TRADING_LEADERSHIP_ENABLED="true"
$env:TRADE_TRADING_LEADERSHIP_LEASE_NAME="okx-primary-account"
$env:TRADE_TRADING_INSTANCE_ID="replace-with-pod-or-task-id"
$env:TRADE_TRADING_LEADERSHIP_LEASE_DURATION_MS="30000"
$env:TRADE_TRADING_LEADERSHIP_HEARTBEAT_INTERVAL_MS="5000"
```

访问同一账户的实例必须使用相同 `lease-name`，每个实例必须使用不同且在该进程生命周期内稳定的
`instance-id`。租约时长至少是心跳间隔的三倍。领导心跳使用数据库时间并独立于决策循环运行；
正常停止会主动释放，进程崩溃后 follower 在租约到期后接管，`fencingToken` 在每次所有权转移时递增。

启用后查看每个实例的 `GET /api/trading/runtime/status`：

- 集群中必须恰好一个实例 `leadership.leader=true`；
- follower 的决策、事件扫描和对账循环会跳过；
- 手工对账发到 follower 会返回 HTTP 409，应转发到当前 leader；
- LIVE 下单在预留订单前和调用 OKX 前都会重新验证数据库租约。

`TRADE_TRADING_LEADERSHIP_REQUIRED_FOR_LIVE` 默认是 `true`。因此即使误开
`execution-mode=live + live-enabled=true`，未启用租约或数据库租约不可用时也不会调用 OKX 下单。
不要为了绕过迁移把该门禁改成 `false`；单实例 PAPER 环境可继续保持 leadership disabled。

## 资金级停止与恢复

策略切换、HTTP stop/resume 和手工对账无需单独的应用令牌。生产环境统一通过网关账号密码和容器网络隔离限制访问，保持后端端口不直接对公网开放；本地开发只在受信任的本机环境调用。

`PUT /api/trading/strategies/active` 请求体仍为 `strategyId` 和可选 `expectedRevision`，版本冲突仍返回 409。浏览器跨域访问只放行 `trade.trading.frontend-allowed-origin-patterns` 中允许的来源，CORS 不替代网关认证。以下命令用于本地后端；生产访问使用网关地址并完成网关认证。

```powershell
# expectedRevision 使用 GET /api/trading/runtime/status 返回的 activeStrategyRevision。
Invoke-RestMethod -Method Put -Headers @{'X-Trade-CSRF'='1'} `
  -Uri http://127.0.0.1:8080/api/trading/strategies/active `
  -ContentType "application/json" `
  -Body '{"strategyId":"threshold-event-defensive","expectedRevision":1}'

Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} `
  -Uri http://127.0.0.1:8080/api/trading/safety/stop `
  -ContentType "application/json" `
  -Body '{"reason":"operator incident response"}'

Invoke-RestMethod http://127.0.0.1:8080/api/trading/safety
```

停止顺序是：MySQL `okx_fund_safety_state=HALTED` -> 设置 OKX cancel-all-after -> 查询并逐笔撤销挂单。应用启动和后续对账循环都会重申该交换所侧停止；若进程失联，则由已设置的 cancel-all-after 在超时后兜底。即使撤单 API 失败，新单仍被本地持久化门禁阻止；此时 `lastActionError` 非空，需要在 OKX 控制台人工核对。

要让停止动作操作真实 OKX 账户，必须选择 `execution-mode=live`；`live-enabled` 可以并建议在事故处理期间保持
`false`。`execution-mode=paper` 下的停止只更新 PAPER 资金域，不会向真实账户发送撤单。

恢复前必须完成订单和仓位对账，确认交易所无挂单、本地无中间态订单，并使用 GET 返回的当前 `version`：

```powershell
Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} `
  -Uri http://127.0.0.1:8080/api/trading/reconciliation/run

$body = @{
  expectedVersion = 3
  reason = "orders and position reconciled by operator"
  confirmation = "RESUME_LIVE_TRADING"
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} `
  -Uri http://127.0.0.1:8080/api/trading/safety/resume `
  -ContentType "application/json" `
  -Body $body
```

不要用 SQL 直接把状态改成 ACTIVE；这会绕过 revision、挂单检查和 dead-man switch 解除流程。

## 观测

X 内容方向、语言、格式、字数与生成协议统一以 [X 工作流](X_WORKFLOW.md) 为准。本手册侧重运行状态与失败定位，不另存一份内容默认值。修改内容配置后只影响新草稿，已有正文仍按保存的规则快照审核和发布。

审核通过只代表 `APPROVED`。发布扫描会把关闭开关、缺凭据、账号不符、本地日额度或间隔等待写入现有 `lastError` 与历史，同一等待原因不反复写入。`FAILED` 的发布前检查和发布请求错误保留安全的 HTTP 状态；`UNKNOWN` 必须在 X 核对，均不会自动重发。automation 循环成功不表示帖子发布成功，需查看草稿状态、`postId`、`lastError` 和历史。

实时决策仅支持 `execution-mode=paper/live`。配置为 `backtest` 时引擎在行情采集前跳过，Broker 也拒绝降级为 PAPER；历史回测必须显式调用 `POST /api/trading/backtests`，不会因修改执行模式自行开始。

| 信号 | 入口 | 关注点 |
| --- | --- | --- |
| 应用存活 | `/actuator/health` | 数据库和应用上下文是否可用 |
| 后台循环 | `/api/automation/tasks` | running、nextRunAt、lastRunSuccessful、lastError |
| Trading 运行状态 | `/api/trading/runtime/status` | 模式、开关和策略运行信息 |
| 多实例领导权 | `/api/trading/runtime/status` | leadership.leader、currentOwnerId、leaseUntil、fencingToken、lastError |
| 资金停止 | `/api/trading/safety` | ACTIVE/HALTED、reason、revision、lastActionError |
| 事件管道 | `/api/trading/runtime/events` | 队列深度、容量、发布/消费快照 |
| 指标 | `/actuator/metrics` | 队列丢弃、handler 失败、耗时和积压趋势 |
| 订单追踪 | `/api/trading/orders/{idempotencyKey}` | 幂等提交与状态历史定位 |

告警应优先覆盖：任务连续失败、事件队列持续接近容量、丢弃率上升、数据库/Redis handler 连续失败、订单长期停留在中间态，以及应用关闭时未在超时内排空。

仓库已提供 Prometheus、Grafana、自动 provisioning 的 trading 仪表盘和 Prometheus 规则。具体启动、指标口径、规则清单和排障步骤见 [Trading 可观测性](OBSERVABILITY.md)。

## 优雅停机

计划停机时：

1. 对 LIVE 账户先执行资金级停止并确认 OKX 无挂单；
2. 停止会产生外部副作用的 automation task；
3. 确认任务 `running=false`；
4. 观察事件队列深度下降；
5. 正常终止 Spring Boot 进程，让生命周期回调停止生产者并在配置超时内排空事件；
6. 核对最后一次任务、订单、成交账本、仓位和风险状态。

不要用强制结束替代正常停机。若异常退出，恢复后先保持自动任务关闭，检查订单账本和交易所状态，再决定是否重新启动；已有幂等记录应走查询/对账路径，不应直接重放下单请求。

## 数据库升级

应用每次启动按 `spring.sql.init.schema-locations` 的显式顺序执行 `db/schema/<module>/schema.sql`。`CREATE TABLE IF NOT EXISTS` 会创建缺失的表，但不会自动补齐已有表字段或修改索引。模块位置见 [数据库目录](../../backend/src/main/resources/db/README.md)，存量库按以下流程升级：

1. 备份并记录当前 schema；
2. 阅读 [db/upgrade/README.md](../../backend/src/main/resources/db/upgrade/README.md) 的依赖和重叠说明，按模块选择适用补丁；
3. 在隔离库执行目标脚本；
4. 验证表、索引、历史数据和应用测试；
5. 在维护窗口执行并保留结果；
6. 应用启动后检查 Mapper 和关键读写路径。

项目当前没有 Flyway/Liquibase 版本表，不能假设按文件名字典序全部执行是安全的。

从旧版 JSON 资金状态迁移至 MySQL 账本时，至少需要先有
`db/upgrade/trading/migration_add_okx_order_idempotency_state_machine.sql`，再执行
`db/upgrade/trading/migration_add_okx_financial_safety_state.sql`，多实例部署随后执行
`db/upgrade/trading/migration_add_okx_trading_leader_lease.sql`。首次启动时，若目标
`account_scope + inst_id` 尚无 MySQL 行，应用会从旧
`data/trading-state.json` 兼容导入一次仓位、成本和风险；导入后应立即
执行对账并核对 `okx_position_state`、`okx_risk_state`。后续 JSON 中的
同名旧字段不再参与资金计算。升级前已终态订单不会自动补写新成交账本，
因为旧 JSON 仓位可能已经包含这些成交，自动重放会导致双计；应把导入
仓位作为升级基线，对疑似旧崩溃窗口单独人工核对。

迁移会把首次出现的 `live` 资金域初始化为 `HALTED`，这是预期的安全默认值。
完成迁移后保持 `live-enabled=false`，先启动 trading 对账或调用手工对账端点；
只有停机之后的订单、仓位对账成功，且交易所与本地都无待处理订单时，才使用
带 revision 和确认短语的 resume 流程。

## 常见故障边界

| 现象 | 首先检查 | 处理原则 |
| --- | --- | --- |
| 应用启动失败 | MySQL、环境变量、配置绑定、Mapper XML | 保持自动任务关闭，修复基础依赖后重启 |
| Trading 事件积压 | handler 耗时/失败、队列策略、Redis/MySQL | 先停止生产任务，保留指标和日志后定位下游 |
| Redis 不可用 | 连接配置、缓存失败计数 | 不依赖缓存的环境可关闭热缓存；不要影响持久化事实源 |
| 自动任务失败 | `lastError`、域 enabled、外部 API | 不做高频人工重试，先确认副作用是否已发生 |
| 订单结果未知 | idempotencyKey、订单账本、OKX 查询 | 先对账再推进状态，禁止直接再次提交 |
| 资金状态 HALTED | `/api/trading/safety`、`lastActionError`、OKX 挂单 | 保持停止，完成人工对账后走带 revision 的 resume |
| 对账连续失败 | automation reconciliation loop、`okx_risk_state` | 不重提订单；修复 API/数据库后先核对中间态和仓位 |
| 没有 trading leader | runtime leadership、租约表、数据库时间 | 保持 LIVE 新单关闭，修复数据库/配置；不要在多个实例上关闭领导门禁 |
| 出现多个 trading leader | leadership 指标、lease-name、数据库是否相同 | 立即资金级停止；确认所有实例指向同一库和同一 lease-name |

## 发布检查清单

- [ ] `clean test` 通过，构建产物来自干净、可追溯的提交；
- [ ] 配置差异和数据库迁移经过评审；
- [ ] 凭据来自密钥系统，日志无敏感信息；
- [ ] 自动任务和真实执行默认值符合目标环境；
- [ ] health、metrics、控制面 API 有网络访问控制；
- [ ] 已准备回滚版本、停机步骤和外部订单处置方案；
- [ ] 发布后按“启动后检查”逐项验收。
