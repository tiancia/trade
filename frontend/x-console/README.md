# X 内容工作台

`frontend/x-console` 是 X 模块的独立前端，使用 Vite + 原生 HTML/CSS/JavaScript。展示最近生成内容、当前版本审核结果、发布结果和历史状态快照，并控制 X 自动工作流。

[X 审核与发布工作流](../../docs/publishing/X_WORKFLOW.md) · [生产 SSH 入口](../../deploy/README.md#4-建立电脑访问隧道)

## 启动

需要 Node.js 22.12 或更新版本。

```powershell
cd A:\trade\frontend\x-console
npm ci
npm run dev
```

打开 `http://127.0.0.1:5175` 后自动读取内容，无需填写应用令牌。生产部署通过网关账号密码限制页面和 API 访问。

默认 `/api` 代理到 `http://127.0.0.1:8080`。后端地址不同时，复制 `.env.example` 到 `.env.local`，设置 `X_API_URL` 并重新启动 Vite。前端不会启动后端或自动开启后台任务。

```powershell
npm test
npm run build
npm run preview
```

`preview` 在 `http://127.0.0.1:4175` 预览构建，并沿用代理设置。部署 `dist/` 时，需要服务器将同源 `/api/x/` 和 `/api/automation/` 转发到后端；静态构建本身不包含 Vite 代理。管理页面和 API 应通过 HTTPS 或本机访问。

## 接口与展示

页面使用同源接口：

- `/api/x/posts?limit=30`：最近内容，可以选择 30、50、100 条。
- `/api/x/posts/{id}`：完整正文、审核人、审核原因、发布 ID、错误与时间。
- `/api/x/posts/{id}/history`：最近 100 条状态快照，按 revision 倒序。
- `GET /api/automation/tasks/x` 和 `POST /api/automation/tasks/x/start|stop`：读取和控制 X 自动循环，写请求附带 `X-Trade-CSRF: 1`。

开关控制当前实例的生成、审核和发布循环，已开始的工作可能继续完成。手动生成 API 独立可用，恢复后符合条件的已审核草稿可能继续发布。这里的启停只在当前进程有效。重启后优先恢复功能控制台保存的模块选择；没有保存选择时，按工作流主开关及显式的 `TRADE_AUTOMATION_X_AUTO_START` 确定。多实例分别控制，操作超时后刷新确认。[后台任务操作](../../docs/backend/OPERATIONS.md#后台任务操作)

统计和筛选只针对本次读取的最近记录，不能代表全量历史。筛选、搜索不会请求生成、审核或发布。页面不提供草稿修改、人工审批、发布或重试按钮；审核继续由后端的 Telegram 回调处理。

`APPROVED` 展示为审核通过、待发布；只有 `PUBLISHED` 且帖子 ID 有效时提供平台链接。`UNKNOWN` 展示为发布结果待核对，不作为发布失败。审核结果和发布结果分别展示；草稿修改后的旧审核可在历史中查看。

刷新失败时保留上次读取的列表，并标记错误和更新时间。生产网关和后端管理 API 均校验操作员 Basic 凭据，后端端口仅在容器网络内提供服务。

## 文件

- `index.html`：工作台与筛选控件。
- `src/main.js`：列表和详情交互、内存中的连接状态、请求取消。
- `src/api.js`：内容与任务接口、超时与响应错误处理。
- `src/model.js`：状态语义、统计、筛选、时间与平台链接。
- `src/styles.css`：桌面和移动端布局。
- `tests/`：离线 API 与状态语义测试；不调用真实后端。
