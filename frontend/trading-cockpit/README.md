# Orbit Trading Cockpit

交易驾驶舱展示 OKX 行情、策略决策、模拟资金、Trading AI 评估和回测结果。页面通过后端 API 读取状态，管理操作使用操作员 Basic 认证，写入自动附带 `X-Trade-CSRF: 1`。[本地后端](../../backend/README.md#快速启动) · [生产 SSH 入口](../../deploy/README.md#4-建立电脑访问隧道)

## 本地启动

需要 Node.js 22.13 或以上版本。在 `frontend/trading-cockpit/` 执行以下命令，打开终端显示的本地地址。

```powershell
npm ci
npm run dev
```

| 连接方式 | 后端地址 |
| --- | --- |
| 本地开发 | 开发服务器默认把 `/api` 代理到 `http://localhost:8080`。可在启动终端用 `TRADE_API_URL` 覆盖 |
| 生产 Docker | 网关转发页面到 Node SSR 服务，转发同源 `/api/` 到后端。[部署方案](../../deploy/README.md#部署方案) |
| Sites / Cloudflare Worker | 在 Worker 运行时设置 `TRADE_API_URL`，转发同源 API 和 K 线 SSE。[Worker 入口](worker/index.ts) |
| 浏览器直连跨域后端 | 构建前设置 `NEXT_PUBLIC_TRADE_API_URL`，同时在后端配置 `TRADE_TRADING_FRONTEND_ALLOWED_ORIGIN_PATTERNS` |

## 页面与数据

首次连接前显示演示数据，任务与回测操作只更新浏览器内的演示快照。运行状态、事件总线、策略、自动任务和回测列表五项请求全部成功后才切换到真实数据。之后若同步中断，页面保留上次快照并暂停启动任务和切换策略。停止任务仍提交给后端，以返回结果确认。[页面入口](app/page.tsx) · [快照同步](app/lib/trading-api.mjs)

| 页面功能 | 使用要点 |
| --- | --- |
| 行情 | SSE 接收 K 线增量，页面可见时每 15 秒核对 HTTP 快照。页面按数据时间判断行情是否过期，标的和币种来自后端运行状态 |
| 策略与模拟资金 | 切换策略、修改参数或删除模拟会话均校验版本。删除会话会一并删除它的模拟成交、资金曲线和风险记录。筛选策略版本时，曲线仍表示整段会话净值。[模拟资金说明](../../docs/trading/SIMULATION.md) |
| Trading AI | 查看评估时间、当前策略、变更前后的参数与执行原因。[策略顾问](../../docs/trading/AI.md) |
| 历史回测 | 默认最近 24 小时，可指定起止时间。提交后从任务列表查看运行结果和失败原因。[回测周期、接口与指标](../../docs/trading/BACKTEST.md) |
| 任务控制 | 驾驶舱启停当前实例的 `trading` 任务。持久化模块开关在[功能控制台](../control-console/README.md)管理 |

## 构建与检查

| 命令 | 用途 |
| --- | --- |
| `npm run build` | 生成部署构建 |
| `npm test` | 构建后运行服务端渲染、API 契约与 Worker 代理测试 |
| `npm run lint` | 静态代码检查 |
| `npx tsc --noEmit --incremental false` | 独立类型检查 |

Windows 与 Docker 构建共用 [font-urls.ts](build/font-urls.ts) 处理 vinext 字体路径，生成的 HTML 应使用站点静态资源 URL。依赖版本及 overrides 维护在 [package.json](package.json) 和[锁文件](package-lock.json)，升级后用 `npm audit` 检查依赖，并用 `npm test` 验证渲染与代理。
