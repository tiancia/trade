# 运维手册

本手册负责后端应用的安全启动、任务控制、资金停止/恢复、观测和业务排障。全项目资料见 [文档总导航](../README.md)，三台机器与端口通信见 [全局上手指南](../PROJECT_GUIDE.md)，Ubuntu Docker、密钥、发布/回滚和备份见 [生产部署说明](../../deploy/README.md)。

下文 Maven 命令从 `backend/` 执行，直连 `127.0.0.1:8080` 的 API 示例面向宿主机运行的 Java 进程。Docker 私有部署通过 SSH 转发后的 `127.0.0.1:18080` 及网关认证访问，不能直接套用本地直连地址。

## 运行依赖

Trading AI 顾问的配置类及 env 示例默认关闭；当前 `application.yml` 运行模板开启了顾问和模拟自动应用，启动前须核对实际环境覆盖。环境变量、独立 Telegram Bot、数据库新增表及启停验收见 [Trading AI 策略顾问](TRADING_AI.md)。它使用独立的 `trading-ai` 自动任务，不阻塞交易决策和对账任务。默认按交易日统计、每 30 分钟复评，以日内净收益和每天 5–8 笔完整交易为规划目标；`TRADE_TRADING_AI_TARGET_MIN_CLOSED_TRADES_PER_DAY` / `TRADE_TRADING_AI_TARGET_MAX_CLOSED_TRADES_PER_DAY` 可调整范围。部署时若已有 `TRADE_TRADING_AI_REVIEW_INTERVAL_MS`，须改为 `1800000` 才使用新节奏。修改后重启后端；已有数据库冷却窗口不会被重启清除，下一轮按新值预留。驾驶舱显示真实下一窗口、生效策略版本和最近执行原因；评估窗口逾期时检查 `trading-ai` 任务运行结果及交易领导租约。次数不足不会强制下单。

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

### Polymarket 未决执行恢复

Polymarket LIVE BUY 执行前使用独立事务预留 `polymarket_execution_guard`。只有正常执行结果和最终审计均提交后才释放；超时、拒绝或不完整确认、进程崩溃及审计失败均保留预留。后续调度会报失败而不再提交真实订单，重启和等待不会解除。该门禁覆盖共享同一数据库的后端实例，当前配置按单账户使用固定 `primary` 名称。

部署时由 Flyway 创建缺失的门禁表；接入前旧结构的适用条件见[数据库升级说明](../../backend/src/main/resources/db/README.md)。首次启用门禁前停止旧版所有 Polymarket 任务并核实已有订单，旧版未决执行不会自动转换为新门禁记录。

恢复步骤：

1. 通过受保护网关停止所有实例的 `polymarket` 自动任务，保持真实执行开关关闭。
2. 查询 `SELECT guard_name, pending_decision_id, updated_at FROM polymarket_execution_guard WHERE guard_name = 'primary';`，按该决策 ID 检查 `polymarket_decision_runs` 和 `polymarket_order_executions` 审计。
3. 在真实 Polymarket 账户核对该时间、token、金额对应的订单和成交，记录已接受、已拒绝或尚无法确定的结论。无法确定时保持阻断；数据库日志或脚本退出码不能替代账户核实。
4. 记录核实依据后，仅释放已核实的决策预留。下列模板默认不更新任何行，必须把 `NULL` 改成实际已核实的决策 ID；核对受影响行数为 1。不要删除门禁行或批量清空未决字段。

```sql
SET @verified_decision_id = NULL; -- 改为实际已核实的决策 ID
UPDATE polymarket_execution_guard
SET pending_decision_id = NULL, updated_at = CURRENT_TIMESTAMP(6)
WHERE guard_name = 'primary' AND pending_decision_id = @verified_decision_id;
```

确认交易所状态与审计记录一致后，才按原有开关和账户验收流程恢复任务。该门禁防止含糊结果后继续下单，不提供自动成交对账或头寸管理。

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

Actuator 当前暴露 health、info、metrics 和 prometheus。automation 启停 API 的应用认证与 CSRF 要求见[后台任务操作](#后台任务操作)；部署时仍须限制后端端口的网络访问，不能直接暴露到公网。

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

X 的 `x` 任务也包含生成、审核、发布三个循环。启动时 Flyway 完成 X 表结构迁移；`db/upgrade/x/` 只用于核实接入前历史结构的前置条件。
配置 `TRADE_X_CONTENT_*`、目标 X 用户 ID 与四个 OAuth 1.0a 凭据，再按
[X 工作流](X_WORKFLOW.md)联调。复用现有 Bot 时，先停止所有实例的微博审核并关闭
`TRADE_WEIBO_TELEGRAM_REVIEW_ENABLED`；应用会拒绝两套审核同时 enabled。
保留两项 X 发布开关关闭直到联调完成；X 外部协议见 [X 客户端](X_API.md)。

查询、启动和停止任务：

管理 API（automation、trading、X、微博和文字游戏管理接口）的 GET 与写请求均由应用独立校验 HTTP Basic 凭据；`TRADE_MANAGEMENT_AUTH_CREDENTIALS_FILE` 指向 bcrypt htpasswd 文件。未配置时这些接口返回 503，配置文件无效时启动失败；生产 Compose 强制开启并挂载网关同一份哈希文件。每人使用独立账号，修改/撤销后重建 backend 与 gateway。仅隔离开发环境可显式关闭 `TRADE_MANAGEMENT_AUTH_ENABLED`。微博 OAuth GET 回调继续通过自身 state 鉴权；浏览器 CORS 预检不执行业务操作。原有业务令牌校验保留。

管理写请求还要求 `X-Trade-CSRF: 1`，浏览器须同源或来自允许的 Origin；无 Origin 的 CLI 同样需要。CSRF 标记不替代身份校验。下面 PowerShell 示例须同时通过 `-Authentication Basic -Credential (Get-Credential)` 提供凭据；仅本机回环 HTTP 调试时 PowerShell 还需显式允许非 HTTPS 认证，生产使用部署网关的 HTTPS 或 SSH 隧道。完整部署、mTLS 公网访问、限速和审计见[部署手册](../../deploy/README.md)。

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/automation/tasks
Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} http://127.0.0.1:8080/api/automation/tasks/trading/start
Invoke-RestMethod -Method Post -Headers @{'X-Trade-CSRF'='1'} http://127.0.0.1:8080/api/automation/tasks/trading/stop
```

启动前检查所属域的 enabled 开关、凭据、风险参数、外部网络和数据库结构。`start` 是幂等的运行状态切换，不会自动把业务 enabled 开关改成 true。

automation `stop` 只阻止后续循环并停止域运行时，不代表资金级停止。真实资金异常应使用下节的持久化停止；该动作会先阻止新单，再尽力撤销挂单，但不会自动市价平仓。

## 多实例单写租约

同一 OKX 资金账户运行多个后端实例时，必须先确认 Flyway 已完成租约表结构迁移；更早旧库的前置条件见[数据库说明](../../backend/src/main/resources/db/README.md)，再在所有实例启用：

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

活动策略选择和 revision 由 `okx_strategy_selection` 按执行模式和标的共享。切换后各实例直接读取数据库，接管不会恢复本地缓存中的旧策略。本次结构随 Flyway V2 自动升级，正常发布无需手工执行旧 SQL；所有共享该库的旧实例都须先停写，并核对旧 leader 的活动策略。表为空时首个实例仅导入一次自己的旧 JSON 选择，不应同时启动内容冲突的旧文件，也不能与旧二进制混跑。

已有仓位但旧文件没有活动策略时，必须先核实并填入共享选择；系统不会在持仓期间自动猜测新策略。升级前由旧版日亏损/回撤触发的 HALTED 也不会自动解除，仍按下述完整对账及显式恢复流程处理。

## 资金级停止与恢复

策略切换、HTTP stop/resume 和手工对账无需单独的应用令牌，仍须通过[管理接口的 Basic 认证与 CSRF 校验](#后台任务操作)。生产后端端口保持在容器网络内；本地开发只在受信任的隔离环境调用。

`PUT /api/trading/strategies/active` 请求体仍为 `strategyId` 和可选 `expectedRevision`，版本冲突仍返回 409。浏览器跨域访问只放行 `trade.trading.frontend-allowed-origin-patterns` 中允许的来源，CORS 不替代网关认证。以下命令用于本地后端；生产访问使用网关地址并完成网关认证。

同库存在受管理 LIVE 持仓、未决订单或当前 PAPER 会话持仓时，策略切换和参数修改返回 409；先完成退出和对账。普通日亏损、回撤及连亏冷却仅阻止加仓，允许已核实持仓减仓。HALTED 专用于人工或对账事故停止，仍禁止所有真实下单，包括卖出，不会自动市价平仓。

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

定时器设置失败后仍尝试查询并撤单，单笔撤单失败后仍处理其他订单；`lastActionError` 聚合这些失败。不能把本地停止成功等同于交易所全部撤单成功。

对账期间，任一订单结算失败会使整轮失败；正常未决订单仍存在时暂缓余额核对，也不刷新完整对账成功时间。现货账户的余额快照只用于核对，不能自动导入受管理仓位或重置成本，即使选择 `dedicated-account`。该模式要求交易所与账本一致，差异触发 HALT；人工存入资产或旧账不一致时须先核清成交与资金来源，不能靠切换该模式接管余额。

OKX 的超时、空响应或缺少完整身份确认会使订单保持 `SUBMIT_UNKNOWN`。先按原 `clOrdId` 查询与结算，禁止因未收到确认就创建新订单；50004 的含糊结果语义见 [OKX 官方说明](https://www.okx.com/en-us/help/api-faq)。累计成交快照缺少有效数量或均价、数值错误或数量回退时保留未决并报告失败，等待取得完整快照后再推进账本。

对于持续返回 51603 的未知单，`GET /api/trading/orders/unresolved` 返回待处理订单及版本。查不到并不能证明从未成交，[OKX 订单查询文档](https://www.okx.com/docs-v5/en/#order-book-trading-trade-get-order-details)规定了历史记录保留范围；系统不会按超时或查询次数自动清除未知单。默认连续三次对账失败仍会 HALT。

人工核实未被接受时，可用 `POST /api/trading/orders/{idempotencyKey}/abandon` 解除该单的对账阻塞，无需改库。先保持资金 HALTED，停止发单工作进程，并独立核查**原账户**的订单历史、成交明细和资产变动；保存完整导出或交易所支持工单等证据。已发现成交、已取得 `ordId` 或尚不能确认的订单必须继续查证并结算，不能 abandon。30 分钟冷却和当前 51603 仅是额外门禁，不能代替人工证据。

请求体示例（通过已认证的管理入口发送，并携带 `X-Trade-CSRF: 1`）：

```json
{
  "expectedVersion": 2,
  "expectedSafetyVersion": 3,
  "clientOrderId": "replace-with-original-clOrdId",
  "operatorReference": "operator-7",
  "reason": "exchange confirmed request not accepted",
  "evidence": "exchange-support-ticket-123",
  "confirmation": "I_VERIFIED_HISTORY_FILLS_AND_ASSETS_ORDER_NOT_ACCEPTED"
}
```

接口要求原 LIVE 账户配置、有效领导权、当前 HALTED 资金版本、当前订单版本、匹配的 `clOrdId`，且订单为 `SUBMIT_UNKNOWN`、无交易所订单 ID、无累计成交；提交及最后变更都须超过 30 分钟。服务重新查询 OKX，必须得到 51603 且该标的无挂单；鉴权失败、网络错误、空响应或查到订单均拒绝。CAS 竞争返回 409，不自动重试。成功后以 `ABANDONED` 终态保留原幂等键与 `clOrdId`、人工说明及状态历史，原订单重放仍不会重新提交；该状态代表人工核查结论，不能当作交易所拒单回执。操作不会恢复资金，且会使原完整对账成功状态失效，必须重新执行下述完整对账与显式 resume。状态字段使用现有 VARCHAR，无需数据库结构迁移。

使用过 abandon 后，镜像回滚目标必须能识别 `ABANDONED` 状态；不应回退到尚未包含该枚举值的后端版本。该账本沿用单个 OKX 账户的部署约定，不应通过更换 API 凭据来处理另一账户的历史订单。

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

后端启动通过 Flyway 执行镜像内尚未应用的版本，`flyway_schema_history` 保存版本、校验值及结果。`spring.sql.init.mode=never`，模块完整 schema 供查阅与离线校验。无历史记录的旧库先核验已知结构，通过后从版本 0 接入；未知或过旧结构会拒绝启动，不能盲目建立基线。开发约定见 [数据库目录](../../backend/src/main/resources/db/README.md)。

标准发布脚本先检查镜像版本、停止旧后端并校验本地 MySQL 备份，再启动新后端迁移；新版本健康且数据库版本符合镜像标签后才记录成功。正常发布不再需要粘贴 SQL。多主机共享库须提前停止其他写入实例。备份位置、失败处置和异机备份见 [部署说明](../../deploy/README.md#数据备份和监控)。

MySQL DDL 失败可能留下部分已提交结构及失败记录；保留停止状态，核实实际结构与数据后恢复或向前修复，不自动 `repair` 或重跑旧 SQL。禁止用旧镜像直接访问更高版本数据库；发布脚本会在停机前拒绝此类回滚。不要扫描执行历史 `db/upgrade/`，其中补丁存在不同适用阶段和重叠定义。

以下仅适用于 Flyway 接入前仍使用更老 JSON 结构的库：迁移至 MySQL 账本时，至少需要先有
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
