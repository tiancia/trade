# 运维手册

本手册描述后端的通用安全运行流程。生产环境的主机、网关、密钥保管和告警平台由部署系统管理，不写入仓库。

## 运行依赖

- JDK 21；
- MySQL，连接信息通过 `SPRING_DATASOURCE_*` 提供；
- Redis 仅用于 trading 热行情缓存。环境没有 Redis 时设置 `TRADE_TRADING_HOT_MARKET_CACHE_ENABLED=false`；
- Polymarket 真实执行额外依赖 Python 与仓库外的桥接脚本；
- 供应商 API 凭据只通过部署环境注入。

`.env.example` 是变量清单，Spring Boot 不会自动加载 `.env`。不要把真实值写回示例文件、启动脚本、日志或问题单。

## 安全启动

首次启动或未知环境中，至少确认以下能力关闭：

```powershell
$env:TRADE_AUTOMATION_STORY_AUTO_START="false"
$env:TRADE_AUTOMATION_TRADING_AUTO_START="false"
$env:TRADE_AUTOMATION_POLYMARKET_AUTO_START="false"
$env:TRADE_TRADING_ENABLED="false"
$env:TRADE_POLYMARKET_ENABLED="false"
$env:TRADE_POLYMARKET_EXECUTION_ENABLED="false"
$env:TRADE_STORY_ENABLED="false"

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
3. `GET /api/automation/tasks` 中任务默认未运行；
4. `GET /api/trading/runtime/events` 的队列容量、深度和消费者状态合理；
5. `GET /api/trading/runtime/status` 中 `fundSafety.status=ACTIVE`、对账无连续失败；若为 `HALTED`，先查明原因，不得直接改库；
6. 若启用 Redis，确认缓存写入成功且没有持续连接重试；
7. 再按任务逐个启用业务开关和 auto-start，避免一次打开多个外部副作用。

Actuator 当前暴露 health、info、metrics 和 prometheus。automation 启停 API 是运维控制面，应用内尚未提供统一运维鉴权；部署时必须由内网、网关或等价访问控制保护，不能直接暴露到公网。

## 后台任务操作

查询、启动和停止任务：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/automation/tasks
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/automation/tasks/trading/start
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/automation/tasks/trading/stop
```

启动前检查所属域的 enabled 开关、凭据、风险参数、外部网络和数据库结构。`start` 是幂等的运行状态切换，不会自动把业务 enabled 开关改成 true。

automation `stop` 只阻止后续循环并停止域运行时，不代表资金级停止。真实资金异常应使用下节的持久化停止；该动作会先阻止新单，再尽力撤销挂单，但不会自动市价平仓。

## 资金级停止与恢复

先在部署环境设置长随机值 `TRADE_TRADING_OPERATOR_TOKEN`；留空时 HTTP stop/resume 端点返回 503，内部硬风控和对账仍能自动停止。不要把 token 放进 URL、日志或仓库。

```powershell
$headers = @{ "X-Trading-Operator-Token" = $env:TRADE_TRADING_OPERATOR_TOKEN }

Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/api/trading/safety/stop `
  -Headers $headers `
  -ContentType "application/json" `
  -Body '{"reason":"operator incident response"}'

Invoke-RestMethod http://127.0.0.1:8080/api/trading/safety
```

停止顺序是：MySQL `okx_fund_safety_state=HALTED` -> 设置 OKX cancel-all-after -> 查询并逐笔撤销挂单。应用启动和后续对账循环都会重申该交换所侧停止；若进程失联，则由已设置的 cancel-all-after 在超时后兜底。即使撤单 API 失败，新单仍被本地持久化门禁阻止；此时 `lastActionError` 非空，需要在 OKX 控制台人工核对。

要让停止动作操作真实 OKX 账户，必须选择 `execution-mode=live`；`live-enabled` 可以并建议在事故处理期间保持
`false`。`execution-mode=paper` 下的停止只更新 PAPER 资金域，不会向真实账户发送撤单。

恢复前必须完成订单和仓位对账，确认交易所无挂单、本地无中间态订单，并使用 GET 返回的当前 `version`：

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/api/trading/reconciliation/run `
  -Headers $headers

$body = @{
  expectedVersion = 3
  reason = "orders and position reconciled by operator"
  confirmation = "RESUME_LIVE_TRADING"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/api/trading/safety/resume `
  -Headers $headers `
  -ContentType "application/json" `
  -Body $body
```

不要用 SQL 直接把状态改成 ACTIVE；这会绕过 revision、挂单检查和 dead-man switch 解除流程。

## 观测

| 信号 | 入口 | 关注点 |
| --- | --- | --- |
| 应用存活 | `/actuator/health` | 数据库和应用上下文是否可用 |
| 后台循环 | `/api/automation/tasks` | running、nextRunAt、lastRunSuccessful、lastError |
| Trading 运行状态 | `/api/trading/runtime/status` | 模式、开关和策略运行信息 |
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

应用每次启动都会执行 `db/ai_trade_mysql_schema.sql`，但其中的 `CREATE TABLE IF NOT EXISTS` 只适合新库初始化，不会自动补齐已有表字段。存量库按以下流程升级：

1. 备份并记录当前 schema；
2. 阅读 `db/migration/README.md` 的依赖和重叠说明；
3. 在隔离库执行目标脚本；
4. 验证表、索引、历史数据和应用测试；
5. 在维护窗口执行并保留结果；
6. 应用启动后检查 Mapper 和关键读写路径。

项目当前没有 Flyway/Liquibase 版本表，不能假设按文件名字典序全部执行是安全的。

本次真实资金闭环升级至少需要先有
`migration_add_okx_order_idempotency_state_machine.sql`，再执行
`migration_add_okx_financial_safety_state.sql`。首次启动时，若目标
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

## 发布检查清单

- [ ] `clean test` 通过，构建产物来自干净、可追溯的提交；
- [ ] 配置差异和数据库迁移经过评审；
- [ ] 凭据来自密钥系统，日志无敏感信息；
- [ ] 自动任务和真实执行默认值符合目标环境；
- [ ] health、metrics、控制面 API 有网络访问控制；
- [ ] 已准备回滚版本、停机步骤和外部订单处置方案；
- [ ] 发布后按“启动后检查”逐项验收。
