# Orbit Trading Cockpit

`frontend/trading-cockpit` 是面向本仓库 Spring Boot 交易模块的独立运行驾驶舱。它集中展示策略运行、最新决策、自动化任务、事件总线背压和回测历史，并保留安全的演示数据降级路径。

## 本地启动

需要 Node.js `>=22.13.0`。

```powershell
npm install
npm run dev
```

开发服务器默认把 `/api` 代理到 `http://localhost:8080`。如后端地址不同，可复制 `.env.example` 为 `.env.local` 并设置 `TRADE_API_URL`。

浏览器直接请求跨域后端或部署前端时，可设置：

```text
NEXT_PUBLIC_TRADE_API_URL=https://your-backend.example.com
```

后端不可达时，页面会清楚标记为“演示数据”，任务启停和回测交互也只修改浏览器内的演示快照，不会触发真实交易。

## 可用命令

- `npm run dev`：启动开发环境
- `npm run build`：生成部署构建
- `npm test`：构建并验证服务端渲染内容
- `npm run lint`：运行静态代码检查

## 接入的后端接口

- `GET /api/trading/runtime/status`
- `GET /api/trading/runtime/events`
- `GET /api/trading/strategies`
- `GET /api/automation/tasks`
- `POST /api/automation/tasks/{taskId}/start|stop`
- `GET|POST /api/trading/backtests`

默认执行模式应继续保持 `PAPER`；前端不会绕过后端的自动化任务、实盘开关和 Broker 路由安全门。
