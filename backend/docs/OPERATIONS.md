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

## 启动后检查

1. `GET /actuator/health` 返回健康；
2. 日志中没有数据库初始化、Mapper 或配置绑定失败；
3. `GET /api/automation/tasks` 中任务默认未运行；
4. `GET /api/trading/runtime/events` 的队列容量、深度和消费者状态合理；
5. 若启用 Redis，确认缓存写入成功且没有持续连接重试；
6. 再按任务逐个启用业务开关和 auto-start，避免一次打开多个外部副作用。

Actuator 当前只暴露 health 和 metrics。automation 启停 API 是运维控制面，应用内尚未提供统一运维鉴权；部署时必须由内网、网关或等价访问控制保护，不能直接暴露到公网。

## 后台任务操作

查询、启动和停止任务：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/automation/tasks
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/automation/tasks/trading/start
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/automation/tasks/trading/stop
```

启动前检查所属域的 enabled 开关、凭据、风险参数、外部网络和数据库结构。`start` 是幂等的运行状态切换，不会自动把业务 enabled 开关改成 true。

`stop` 只阻止后续循环并停止域运行时，不代表撤销交易所已有挂单、平仓或回滚已经发布的外部内容。真实资金应另有交易所侧的撤单/仓位处置预案。

## 观测

| 信号 | 入口 | 关注点 |
| --- | --- | --- |
| 应用存活 | `/actuator/health` | 数据库和应用上下文是否可用 |
| 后台循环 | `/api/automation/tasks` | running、nextRunAt、lastRunSuccessful、lastError |
| Trading 运行状态 | `/api/trading/runtime/status` | 模式、开关和策略运行信息 |
| 事件管道 | `/api/trading/runtime/events` | 队列深度、容量、发布/消费快照 |
| 指标 | `/actuator/metrics` | 队列丢弃、handler 失败、耗时和积压趋势 |
| 订单追踪 | `/api/trading/orders/{idempotencyKey}` | 幂等提交与状态历史定位 |

告警应优先覆盖：任务连续失败、事件队列持续接近容量、丢弃率上升、数据库/Redis handler 连续失败、订单长期停留在中间态，以及应用关闭时未在超时内排空。

## 优雅停机

计划停机时：

1. 停止会产生外部副作用的 automation task；
2. 确认任务 `running=false`；
3. 观察事件队列深度下降；
4. 正常终止 Spring Boot 进程，让生命周期回调停止生产者并在配置超时内排空事件；
5. 核对最后一次任务、订单和持久化状态。

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

## 常见故障边界

| 现象 | 首先检查 | 处理原则 |
| --- | --- | --- |
| 应用启动失败 | MySQL、环境变量、配置绑定、Mapper XML | 保持自动任务关闭，修复基础依赖后重启 |
| Trading 事件积压 | handler 耗时/失败、队列策略、Redis/MySQL | 先停止生产任务，保留指标和日志后定位下游 |
| Redis 不可用 | 连接配置、缓存失败计数 | 不依赖缓存的环境可关闭热缓存；不要影响持久化事实源 |
| 自动任务失败 | `lastError`、域 enabled、外部 API | 不做高频人工重试，先确认副作用是否已发生 |
| 订单结果未知 | idempotencyKey、订单账本、OKX 查询 | 先对账再推进状态，禁止直接再次提交 |

## 发布检查清单

- [ ] `clean test` 通过，构建产物来自干净、可追溯的提交；
- [ ] 配置差异和数据库迁移经过评审；
- [ ] 凭据来自密钥系统，日志无敏感信息；
- [ ] 自动任务和真实执行默认值符合目标环境；
- [ ] health、metrics、控制面 API 有网络访问控制；
- [ ] 已准备回滚版本、停机步骤和外部订单处置方案；
- [ ] 发布后按“启动后检查”逐项验收。

