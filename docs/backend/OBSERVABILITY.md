# Trading 可观测性

本页主要说明本地开发环境的指标与监控。全项目环境见 [上手指南](../PROJECT_GUIDE.md)，生产容器的启用方式见 [部署说明](../../deploy/README.md)；本地与生产使用不同 Compose 和抓取配置。

本项目用 Spring Boot Actuator + Micrometer 暴露指标，由 Prometheus 抓取和计算告警，Grafana 负责查询与展示。监控服务已纳入仓库根目录 `A:\trade\compose.yaml` 统一管理，专属配置位于 `backend/infrastructure/observability/`；启动基础设施不会开启 trading、自动任务或真实下单开关。

本地开发提供指标、看板和规则计算；生产 `monitoring` profile 还提供 Alertmanager 投递，须先配置真实外部接收端并验收到达。日志仍由应用日志系统负责；跨服务日志检索与调用链追踪需单独接入。

## 数据流

```text
Trading runtime
  └─ Micrometer metrics
      └─ GET /actuator/prometheus
          └─ Prometheus (15s scrape + rules)
              └─ Grafana (provisioned datasource + dashboard)
```

Prometheus 默认从 `host.docker.internal:8080/actuator/prometheus` 抓取宿主机上的后端。Docker Compose 为 Linux 同时配置了 `host-gateway`。如果后端不在宿主机的 `8080` 端口，修改 `backend/infrastructure/observability/prometheus/prometheus.yml` 的 target；如果后端也运行在容器中，改成对应 Compose 服务 DNS。

生产 `compose.prod.yaml` 已通过可选 monitoring profile 使用 [deploy/prometheus/prometheus.yml](../../deploy/prometheus/prometheus.yml)，抓取目标为 `backend:8080`，不需要改成本地的 host.docker.internal。生产监控入口默认为宿主机回环 9090 / 3001；下文 3000 是本地 Grafana 入口。

## 本地启动

前置条件：

- 后端所需的 JDK 21；
- Docker Desktop 或 Docker Engine；
- Docker Compose v2。

先从仓库根目录 `A:\trade` 创建本地基础设施配置并启动全部中间件；模板中的本地开发密码统一为 `123456`：

```powershell
Copy-Item .env.example .env
docker compose up -d
```

把仓库根目录 `.env` 中的 MySQL/Redis 账号密码同步到 `backend/.env` 的后端连接变量；完整对应关系见 [基础设施说明](../../backend/infrastructure/README.md)。然后进入 `backend/` 启动后端，并保持危险能力关闭：

```powershell
cd A:\trade\backend
$env:TRADE_AUTOMATION_TRADING_AUTO_START="false"
$env:TRADE_TRADING_ENABLED="false"
$env:TRADE_METRICS_ENVIRONMENT="local"

.\mvnw.cmd spring-boot:run
```

确认 Prometheus 文本端点可访问：

```powershell
Invoke-WebRequest http://127.0.0.1:8080/actuator/prometheus |
  Select-Object -ExpandProperty Content |
  Select-String "trade_trading_events_queue_capacity"
```

如果之前只启动了 MySQL 和 Redis，可单独启动监控服务：

```powershell
cd A:\trade
docker compose up -d prometheus grafana
```

访问入口：

- Prometheus targets：`http://127.0.0.1:9090/targets`，`trade-backend` 应为 `UP`；
- Prometheus rules：`http://127.0.0.1:9090/rules`；
- Grafana：`http://127.0.0.1:3000`；
- Grafana 仪表盘：`Dashboards -> Trade -> Trading Module Overview`。

Grafana 数据源和仪表盘由 provisioning 自动创建，不需要手工导入 JSON。

停止服务但保留历史数据：

```powershell
docker compose down
```

MySQL、Redis、Prometheus 与 Grafana 都使用命名卷保存数据。只有明确不再需要本地数据时，才额外使用 `down --volumes`。

## 指标分层

Micrometer 的点号名称在 Prometheus 中转换为下划线；Counter 还会增加 `_total` 后缀。例如 `trade.trading.events.dropped` 对应 `trade_trading_events_dropped_total`。

| 业务面 | 主要指标 | 关键标签或含义 |
| --- | --- | --- |
| 应用与 HTTP | `up`、`http_server_requests_seconds_*`、`jvm_*`、`process_*` | 存活、5xx、p95、堆内存、CPU |
| 自动任务（含 story/Weibo/X） | `trade_automation_runs_total`、`trade_automation_duration_seconds_*`、`trade_automation_last_success` | 固定 task/loop；outcome=success/failure/skipped；最近结果 -1 未运行、0 失败、1 成功 |
| 策略决策 | `trade_trading_decisions_runs_total`、`trade_trading_decisions_duration_seconds_*`、`trade_trading_decisions_actions_total`、`trade_trading_decisions_running` | trigger、outcome、action、execution_status |
| 风控 | `trade_trading_risk_assessments_total`、`trade_trading_risk_violations_total` | allowed/blocked、primary_rule、rule |
| 事件总线 | `trade_trading_events_queue_*`、`trade_trading_events_published_total`、`trade_trading_events_dropped_total`、`trade_trading_events_handled_total` | 队列容量、背压、丢弃原因、handler 结果 |
| 事件耗时 | `trade_trading_events_queue_latency_seconds_*`、`trade_trading_events_handler_duration_seconds_*` | type、handler；已开启直方图 |
| 订单状态机 | `trade_trading_orders_reservations_total`、`trade_trading_orders_transitions_total`、`trade_trading_orders_cas_conflicts_total` | 幂等重放、from/to、乐观锁冲突 |
| 订单对账 | `trade_trading_reconciliation_runs_total`、`trade_trading_reconciliation_duration_seconds_*`、`trade_trading_reconciliation_running` | completed/failed/busy/pending（仍有未决订单，暂缓余额核对）、连续失败与耗时 |
| 多实例领导权 | `trade_trading_leadership_enabled`、`trade_trading_leadership_active`、`trade_trading_leadership_operations_total` | 每个资金集群应恰好一个 leader；outcome、trigger |
| 资金停止 | `trade_trading_fund_safety_status`、`trade_trading_fund_safety_actions_total` | status：-1 未知、0 ACTIVE、1 HALTED；动作：halted/resumed/cancel_failed |
| Redis 热行情 | `trade_trading_hot_market_cache_operations_total` | operation、kind、hit/miss/failed/retry_deferred |
| K 线 SSE | `trade_trading_candles_stream_clients`、`trade_trading_candles_stream_queue_depth`、`*_dropped_total`、`*_failed_total` | 客户端、I/O 队列和背压 |

对账的 `pending` 表示订单仍未决，不刷新完整对账成功时间，也不清除既有连续失败计数。

自动任务的禁用、无工作和额度跳过属于正常返回，可能显示最近成功；真实生成失败、降级产物、审核投递失败及发布 FAILED/UNKNOWN 会反馈失败。最近结果随后续成功刷新，累计 failure counter 保留失败证据；互斥忙计 skipped，不覆盖上次结果。任务 API 不返回原始供应商异常文本。人工未知订单解除另计 `trade_trading_orders_manual_recovery_total{outcome="abandoned"}`，操作细节保留在订单状态审计中。

所有 Spring 指标都带有：

- `application=trade`；
- `environment=${TRADE_METRICS_ENVIRONMENT}`，本地默认值为 `local`。

不要把订单 ID、用户 ID、instrument 动态值、异常消息或幂等键放进指标标签；这些值会制造高基数时间序列，应放在日志或审计记录中。

## 仪表盘

`Trading Module Overview` 覆盖：

- 后端抓取状态、当前 firing alerts；
- 事件队列利用率、吞吐、丢弃、p95 排队和 handler 耗时；
- 策略运行结果、动作与执行状态、决策 p95；
- 风控允许/阻断及规则分布；
- 订单状态迁移、幂等重放、CAS 冲突和 `SUBMIT_UNKNOWN`；
- Redis 热行情缓存命中/失败/退避；
- JVM、HTTP 和浏览器 K 线 SSE。

图表为空不一定是异常：Counter 和 Timer 在对应路径第一次执行前不会产生时间序列。例如 trading 默认关闭时，不会出现真实策略动作、风控或订单迁移数据。

## 告警规则

`backend/infrastructure/observability/prometheus/rules/trading-alerts.yml` 包含以下告警：

- 后端不可抓取、HTTP 5xx 持续升高；
- 事件队列持续超过 80%、事件丢弃、handler 失败、p95 排队超过 1 秒；
- 策略决策失败；
- 订单进入 `SUBMIT_UNKNOWN`、CAS 冲突异常升高；
- 订单对账失败、资金状态为 HALTED/未知、停止后的撤单失败；
- 已启用租约但集群无 leader、出现多个 leader、租约刷新失败；
- Redis 热行情缓存降级；
- K 线 SSE 更新被背压丢弃。

生产 Prometheus 已通过 `alerting` 连接 Alertmanager，`operations-alerts.yml` 另外覆盖非交易自动任务过去 15 分钟的失败、Alertmanager 不可用与通知投递失败。外部 HTTPS 接收端由 secret 配置，缺少有效接收端时 Alertmanager 拒绝启动；本地开发 Compose 仍只计算规则。实际启用、接收端配置与真实投递验收见[部署说明](../../deploy/README.md#告警投递与独立存活监控)。critical 告警仍须明确负责人、静默与升级策略；另用外部存活监控覆盖整机/网络失效及通知通道自身失效。

`SUBMIT_UNKNOWN` 告警不能通过盲目重试处理。先使用订单幂等键和 `clOrdId` 对账 OKX；长期 51603 的人工证据解除流程见[运维手册](OPERATIONS.md)。查不到不是未成交证明，解除后也保持 HALTED，须重新完整对账和显式恢复。

## 安全与生产化

- Compose 默认只把 Prometheus 和 Grafana 绑定到 `127.0.0.1`，且 Grafana 密码必须显式设置；
- `/actuator/prometheus` 当前没有应用内统一鉴权，不能直接暴露到公网；生产环境应使用独立 management 网络、网关白名单或等价访问控制；
- 不要在 Grafana dashboard、Prometheus labels、规则 annotations 或 Compose 文件中写入 API key、订单凭据和用户数据；
- 生产部署要持久化并备份时序数据，按采样频率、序列数和保留期计算容量；
- 多实例部署时保留 `instance` 维度，额外通过部署系统注入稳定的 cluster/region 标签；
- 修改规则后先运行 `promtool check config` 和 `promtool check rules`，再 reload 或滚动重启 Prometheus。

## 排障

### Prometheus target 为 DOWN

1. 直接访问 `http://127.0.0.1:8080/actuator/prometheus`；
2. 确认后端端口与 `prometheus.yml` 的 target 一致；
3. 在 Prometheus 容器内检查 `host.docker.internal` 是否解析；
4. 检查宿主机防火墙是否允许 Docker 虚拟网络访问后端端口；
5. 如果后端在另一个容器中，使用容器服务名和共享网络，不再使用宿主机地址。

### Grafana 没有数据

1. 先看 Prometheus targets 是否为 `UP`；
2. 在 Grafana Connections 中确认 UID 为 `prometheus` 的数据源健康；
3. 检查 dashboard 的 Backend instance 变量；
4. 拉长时间范围并实际触发对应业务路径；
5. 核对 Micrometer 点号名称与 Prometheus 下划线名称的转换。

### p95 面板为空

直方图只从应用启用本次配置并重启后开始产生 bucket。确认 `application.yml` 中对应 `percentiles-histogram` 为 `true`，再执行产生该 Timer 的业务路径。
