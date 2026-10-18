# Orbit Trading Cockpit

`frontend/trading-cockpit` 是面向本仓库 Spring Boot 交易模块的独立运行驾驶舱。它集中展示实时 K 线、当前策略、最新决策、自动化任务、事件总线背压和回测历史，并保留安全的演示数据降级路径。

## 本地启动

需要 Node.js `>=22.13.0`。

```powershell
npm install
npm run dev
```

开发服务器默认把 `/api` 代理到 `http://localhost:8080`。如后端地址不同，可复制 `.env.example` 为 `.env.local` 并设置 `TRADE_API_URL`。

浏览器直接请求跨域后端时，可在构建前设置：

```text
NEXT_PUBLIC_TRADE_API_URL=https://your-backend.example.com
```

Sites 部署推荐设置运行时 `TRADE_API_URL=https://your-backend.example.com`，站点 Worker 会把同源 `/api/*` 请求（包括 K 线 SSE 流）转发到该后端；未设置时继续展示安全演示快照。

后端不可达时，页面会清楚标记为“演示数据”，任务启停和回测交互也只修改浏览器内的演示快照，不会触发真实交易。

实时 K 线优先使用后端 SSE 增量推送，连接中断时每 15 秒读取一次最新快照。策略页的“设为当前策略”会携带后端版本号提交，避免多个操作员页面静默互相覆盖。线上跨域部署时，还需在后端设置 `TRADE_TRADING_FRONTEND_ALLOWED_ORIGIN_PATTERNS`。

切换策略前，在左侧“设置”填写服务器 `TRADE_TRADING_OPERATOR_TOKEN`。令牌仅保存在当前页面内存，刷新后需重新输入；仅策略切换请求携带 `X-Trading-Operator-Token`，不能用网关密码或 X 管理令牌替代。

新建回测默认最近 24 小时，可修改本地起止时间；提交时转换为 ISO 时间并检查开始时间早于结束时间。回测提交成功只表示进入队列，运行失败原因会显示在回测列表中。时间展示兼容后端秒级时间戳和 ISO 字符串。

连接后端后，尚未接入资产、盈亏和风险利用率汇总的卡片显示“暂无数据”；固定示例数值只在演示模式显示。WebSocket 未就绪不等于 REST 行情不可用。

## 可用命令

- `npm run dev`：启动开发环境
- `npm run build`：生成部署构建
- `npm test`：构建并验证服务端渲染、回测参数、操作员请求头和时间戳契约
- `npm run lint`：运行静态代码检查

## 接入的后端接口

- `GET /api/trading/runtime/status`
- `GET /api/trading/runtime/events`
- `GET /api/trading/strategies`
- `PUT /api/trading/strategies/active`
- `GET /api/trading/market/candles`
- `GET /api/trading/market/candles/stream`（SSE）
- `GET /api/automation/tasks`
- `POST /api/automation/tasks/{taskId}/start|stop`
- `GET|POST /api/trading/backtests`

默认执行模式应继续保持 `PAPER`；前端不会绕过后端的自动化任务、实盘开关和 Broker 路由安全门。
