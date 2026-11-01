# 功能控制台

独立的 React + TypeScript + Vite 前端项目，统一管理 OKX 策略交易、Trading AI、Polymarket、AI 故事、微博和 X 工作流。入口是本站根路径 `/`，与交易驾驶舱分别安装、启动、构建和部署。

## 本地运行

需要 Node.js 22.13+。在 PowerShell 中运行：

```powershell
cd A:\trade\frontend\control-console
npm ci
npm run dev
```

打开 <http://127.0.0.1:5176>。后端需要单独启动；默认通过 Vite 将 `/api` 代理到 `http://127.0.0.1:8080`，代理保留请求 Host，以便后端进行同源校验。其他后端地址可复制 `.env.example` 为 `.env.local` 后修改 `CONTROL_API_URL`，随后重启开发服务器。

管理接口沿用后端的操作员 Basic 认证。后端返回认证挑战时，使用已配置的操作员账号登录；未配置凭据或后端不可达时，页面显示错误并禁用开关。不要在前端环境文件中保存操作员口令。

```powershell
npm test
npm run build
npm run preview
```

构建产物为 `dist/`，本地预览地址为 <http://127.0.0.1:4176>，同样代理 `/api`。

## 开关行为

- 每个模块一个主开关，同时控制模块门禁与对应后台任务；页面每 10 秒刷新实际状态。
- 选择保存在后端数据库，重启后恢复。页面分别显示已保存选择、任务实际运行状态和失败原因，启动失败可重试或关闭。
- 主开关不替代真实交易、自动发布、资金安全停机等独立授权；开启模块仍遵守原有门禁。
- 关闭阻止后续调度，不撤回已发出的外部请求，也不保证中断正在执行的任务。控制立即作用于当前后端实例，不广播到其他实例。

接口为 `GET /api/automation/modules` 和 `PUT /api/automation/modules/{id}`，写入发送 `{"enabled":true/false}` 与 `X-Trade-CSRF: 1`。具体语义见[后端运维手册](../../docs/backend/OPERATIONS.md)。

## 生产部署

独立镜像服务名为 `control-console`，构建上下文为本目录。生产由 Nginx gateway 提供认证和 API 代理，静态容器不直接暴露后端 API。

- 默认 SSH 私有入口：<http://127.0.0.1:18082>，配置项 `CONTROL_CONSOLE_LOCAL_PORT`。
- 公共 HTTPS 模式：配置 `CONTROL_CONSOLE_DOMAIN`，证书覆盖该域名。
- CNB 发布入口可选择 `service=control-console`；`frontends` 包含三个前端，`all` 包含全部服务。

从旧版升级时，首次需要构建并部署 `all`，同步网关配置并建立新服务，之后可单独发布。完整步骤和 SSH 端口转发见[生产部署说明](../../deploy/README.md)。
