# Orbit Trading Cockpit

`frontend/trading-cockpit` 是面向本仓库 Spring Boot 交易模块的独立运行驾驶舱。它集中展示实时 K 线、当前策略、最新决策、自动化任务、事件总线背压和回测历史，并保留安全的演示数据降级路径。

[项目文档总导航](../../docs/README.md) · [系统通信与接手指南](../../docs/PROJECT_GUIDE.md) · [生产 Docker 部署](../../deploy/README.md)

## 本地启动

导航中的「功能管理」打开 `/controls`，统一管理 OKX 交易、Trading AI、Polymarket、故事生成、微博和 X。通过 `GET /api/automation/modules` 读取状态、`PUT /api/automation/modules/{id}` 提交 `{"enabled":true|false}`；页面使用真实后端状态，连接失败时禁用操作。开关会更新业务模块和任务运行状态，并保存到数据库以便重启恢复。启用失败显示实际状态，可重试或关闭已保存的选择。安全授权和操作边界见[后台任务操作](../../docs/backend/OPERATIONS.md#后台任务操作)。

需要 Node.js `>=22.13.0`。以下命令在 `frontend/trading-cockpit/` 执行。

```powershell
npm install
npm run dev
```

开发服务器默认把 `/api` 代理到 `http://localhost:8080`。如后端地址不同，可复制 `.env.example` 为 `.env.local` 并设置 `TRADE_API_URL`。

浏览器直接请求跨域后端时，可在构建前设置：

```text
NEXT_PUBLIC_TRADE_API_URL=https://your-backend.example.com
```

当前生产方案使用 Docker Node SSR，由 gateway 转发同源 `/api/`，按生产部署说明操作。若另外选择 Sites/Cloudflare Worker 部署，可设置运行时 `TRADE_API_URL=https://your-backend.example.com`，站点 Worker 会把同源 `/api/*` 请求（包括 K 线 SSE 流）转发到该后端；未设置时继续展示安全演示快照。这是另一种部署方式，不是当前生产入口。

Worker 保留请求的 Origin、CSRF 标记及认证头，先清除调用方的 `Forwarded`、`X-Forwarded-*` 和 `X-Real-IP`，再根据实际站点 URL 重建转发主机、协议与端口，避免客户端伪造后端的有效请求来源。

首次连接前，页面清楚标记为“演示数据”，任务启停和回测交互只修改浏览器内的演示快照。运行状态、事件总线、策略、自动化任务、回测列表这 5 路接口全部成功后，才整体替换为真实快照，避免将演示 PAPER 状态或虚构任务混入真实数据。

连接成功后若同步失败，页面保留上次真实快照并显示“同步中断”，不会回退为演示操作。未重新同步前禁止启动自动化或切换策略；停止命令仍会提交后端，并以接口返回结果确认，失败时显示错误。交易开关只操作 ID 为 `trading` 的任务，未读取到该任务时禁用，避免误操作其他自动化。JSON 请求默认禁止缓存并在 15 秒后超时；新刷新会取消旧刷新，任务启停或策略切换成功后，较早发出的刷新结果不能覆盖操作结果。

实时 K 线优先使用后端 SSE 增量推送，页面可见时始终每 15 秒读取最新快照，补偿连接中断或丢失的增量。后端默认订阅 1m、5m、15m、1H、4H；缓存过旧或缺少历史时主动请求 OKX REST。页面同时检查最近 30 秒是否收到数据，以及最新 K 线是否属于当前周期，过期时显示“行情已过期”，SSE 连通本身不会显示行情正常。策略页的“设为当前策略”会携带后端版本号提交，避免多个操作员页面静默互相覆盖。线上跨域部署时，还需在后端设置 `TRADE_TRADING_FRONTEND_ALLOWED_ORIGIN_PATTERNS`。

查看页面和切换策略无需填写应用令牌；生产访问由网关账号密码及网络隔离保护，后端端口不直接对公网开放。策略切换仍携带版本号，版本冲突时需要刷新后重试。

前端对管理 POST / PUT / PATCH / DELETE 自动附加 `X-Trade-CSRF: 1`，后端同时检查浏览器 Origin。直接调用管理 API 的命令行也须带该头；它不是认证令牌，不需要用户输入。

新建回测默认最近 24 小时，可修改本地起止时间；提交时转换为 ISO 时间并检查开始时间早于结束时间。回测提交成功只表示进入队列，运行失败原因会显示在回测列表中。时间展示兼容后端秒级时间戳和 ISO 字符串。

行情 REST/SSE 和回测默认标的跟随运行状态的 `instId`，币种标签使用 `baseCcy` / `quoteCcy`；旧后端缺少字段时保持兼容回退。切换标的或周期时不会把上一条行情的K线合入新序列。回测默认资金采用当前PAPER批次的初始资金，无会话时为1000；历史回测支持1m/5m/15m，日内技术策略固定15m。图表仍可查看1H/4H，但不将它们作为策略回测周期。后端会拒绝历史缺口、覆盖不足或 `maxCandles` 不够的请求。

策略页的“修改参数 / 查看版本”读取后端的完整有效参数。保存时携带配置版本号和修改原因，生成不可修改的新版本；参数只对后续新决策生效。版本冲突会保留页面输入，须重新加载最新配置后再修改。历史版本展开后可看到完整值及相对上一个版本的变化。价格与浮亏触发比例 `0.02` 表示 `2%`；K 线窗口还受部署的行情采集上限约束，下单参数仍受全局风控和单笔金额上限约束。

`intraday-technical` 提供三种模式及23个参数，保存前检查快慢EMA、RSI/ATR上下界及止盈大于止损的关系，并保留服务端BigDecimal需要的精确文本；整数周期不会因浮点舍入而接受非整数。未知策略类型的当前参数和历史可只读查看，前端不为其发送不受支持的保存请求。

PAPER 总览支持设置初始资金、创建独立模拟会话，查看现金、持仓、净值、已实现与未实现盈亏、累计收益率、手续费、资金曲线与成交明细。新会话从零持仓开始，旧会话归档保留；不能直接覆盖已有会话的初始资金。初始资金最多 8 位小数，上限 1 万亿。创建请求使用当前会话 ID 和版本号检查并发变化，失败时不会显示为创建成功。运行模式未确认或处于 LIVE 时，不开放模拟资金操作。

所选当前或历史会话可通过“删除此会话”并确认后永久删除，连同该会话的模拟成交、资金曲线及风险状态一起清理。删除当前会话后需重新创建模拟资金，不会激活旧会话。请求携带所选会话版本，遇到新成交或其他并发变化时须重新加载确认；超时和失败不会被显示为删除成功。相关数据和并发语义见 [模拟资金说明](../../docs/backend/TRADING_SIMULATION.md#删除模拟会话)。

成交和资金记录可按策略及配置版本筛选。曲线始终展示整段会话净值，并高亮选中版本的记录；版本筛选不把延续持仓产生的累计盈亏当作独立策略收益。需要控制初始资金独立比较时，应分别创建模拟会话。会话列表默认读取最近 20 个，成交与资金记录分别读取最近 100 条，可加载更早记录。估值超过有效期会明确标记为过期。风险利用率仍未接入汇总，连接后端时保持“暂无数据”。WebSocket 未就绪不等于 REST 行情不可用。

## 可用命令

- `npm run dev`：启动开发环境
- `npm run build`：生成部署构建
- `npm test`：构建并验证服务端渲染、配置版本请求、模拟资金请求、记录筛选、回测参数和时间戳契约
- `npm run lint`：运行静态代码检查
- `npx tsc --noEmit --incremental false`：独立执行完整类型检查

`build/font-urls.ts` 为vinext在Windows上的字体缓存路径补齐站点相对URL，普通构建和Docker Node SSR构建共用；Dockerfile及忽略白名单均需包含此文件。生产HTML不得出现 `file:///` 或本机磁盘字体URL，字体文件应从站点的静态资源路径返回。

当前使用 `vinext` 0.0.55，包含 Windows 静态资源路径修复。其固定依赖的 `image-size` 2.0.2 存在图片解析死循环漏洞，因此只对该依赖覆盖为兼容的 2.0.4。升级 `vinext` 后需重新检查此覆盖是否仍有必要，并重新运行 `npm audit`；审计结果随供应商公告与依赖变化。

## 接入的后端接口

- `GET /api/trading/runtime/status`
- `GET /api/trading/runtime/events`
- `GET /api/trading/strategies`
- `PUT /api/trading/strategies/active`
- `GET|PUT /api/trading/strategies/{id}/configuration`
- `GET /api/trading/strategies/{id}/configuration/history`
- `GET /api/trading/paper/account`
- `GET|POST /api/trading/paper/sessions`
- `GET /api/trading/paper/sessions/{sessionId}`
- `DELETE /api/trading/paper/sessions/{sessionId}?expectedVersion={version}`
- `GET /api/trading/paper/sessions/{sessionId}/equity`
- `GET /api/trading/paper/sessions/{sessionId}/fills`
- `GET /api/trading/market/candles`
- `GET /api/trading/market/candles/stream`（SSE）
- `GET /api/automation/tasks`
- `POST /api/automation/tasks/{taskId}/start|stop`
- `GET|POST /api/trading/backtests`

默认执行模式应继续保持 `PAPER`；前端不会绕过后端的自动化任务、实盘开关和 Broker 路由安全门。
