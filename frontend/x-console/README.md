# X 内容工作台

`frontend/x-console` 是 X 模块的独立展示前端，使用 Vite + 原生 HTML/CSS/JavaScript。展示最近生成内容、当前版本审核结果、发布结果和历史状态快照。

## 启动

需要 Node.js 22.12 或更新版本。

```powershell
cd A:\trade\frontend\x-console
npm install
npm run dev
```

打开 `http://127.0.0.1:5175`，点击「连接后端」，输入后端配置的 `TRADE_X_ADMIN_TOKEN`。令牌只保留在页面内存中，刷新或断开连接后清除，不写入浏览器存储、前端环境变量或构建产物。

默认 `/api` 代理到 `http://127.0.0.1:8080`。后端地址不同时，复制 `.env.example` 到 `.env.local`，设置 `X_API_URL` 并重新启动 Vite。前端不会启动后端或自动开启后台任务。

```powershell
npm test
npm run build
npm run preview
```

`preview` 在 `http://127.0.0.1:4175` 预览构建，并沿用代理设置。部署 `dist/` 时，需要服务器将同源 `/api/x/` 转发到后端；静态构建本身不包含 Vite 代理。管理页面和 API 应通过 HTTPS 或本机访问。

## 接口与展示

所有请求均为 GET，携带 `X-X-Admin-Token`：

- `/api/x/posts?limit=30`：最近内容，可以选择 30、50、100 条。
- `/api/x/posts/{id}`：完整正文、审核人、审核原因、发布 ID、错误与时间。
- `/api/x/posts/{id}/history`：最近 100 条状态快照，按 revision 倒序。

统计和筛选只针对本次读取的最近记录，不能代表全量历史。筛选、搜索不会请求生成、审核或发布。页面不提供草稿修改、人工审批、发布或重试按钮；审核继续由后端的 Telegram 回调处理。

`APPROVED` 展示为审核通过、待发布；只有 `PUBLISHED` 且帖子 ID 有效时提供平台链接。`UNKNOWN` 展示为发布结果待核对，不作为发布失败。审核结果和发布结果分别展示；草稿修改后的旧审核可在历史中查看。

未配置后端管理员令牌时，X 控制器不会注册，可能返回 404；令牌错误返回 401。接口不可达、响应异常和刷新失败会明确提示，不生成或回退到演示记录。刷新失败时保留上次成功读取的列表，并标记读取异常和更新时间。

## 文件

- `index.html`：工作台、筛选控件与连接设置。
- `src/main.js`：列表和详情交互、内存中的连接状态、请求取消。
- `src/api.js`：三个只读接口、超时与响应错误处理。
- `src/model.js`：状态语义、统计、筛选、时间与平台链接。
- `src/styles.css`：桌面和移动端布局。
- `tests/`：离线 API 与状态语义测试；不调用真实后端。
