# Trade

Trade 用一个 Spring Boot 后端运行 OKX 策略交易、Polymarket 决策，以及 X 和微博的草稿审核与发布。三个前端独立构建，共用后端 API。阅读代码可从[应用入口](backend/src/main/java/com/trade/TradeApplication.java)和[后端模块目录](docs/backend/ARCHITECTURE.md#模块目录)开始。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| [backend/](backend/README.md) | Java 21、Spring Boot 4、MyBatis。业务代码按域组织，SQL、迁移和 Mapper 在 `src/main/resources/` |
| [frontend/](frontend/) | `trading-cockpit` 查看行情、策略和模拟资金；`x-console` 查看草稿及启停 X 任务；`control-console` 管理各模块开关 |
| [tools/polymarket/](tools/polymarket/README.md) | 后端调用的 Python 下单桥接、调试工具和样例 |
| [deploy/](deploy/README.md) | 生产配置模板、网关、代理、镜像构建、发布和回滚脚本 |
| [.github/workflows/](.github/workflows/cnb-build-deploy.yml) | 手动提交 CNB 构建或发布任务的入口 |
| [docs/](docs/README.md) | `backend/` 讲架构与运维，`trading/` 讲模拟、回测和策略顾问，`publishing/` 讲审核工作流和平台客户端 |

## 本地上手

1. 在 Windows 仓库根目录准备本地配置，启动 MySQL 和 Redis。监控服务和数据位置见 [本地基础设施](backend/infrastructure/README.md)。

   ```powershell
   if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
   docker compose up -d mysql redis
   ```

2. 安装 JDK 21，进入 `backend/`，使用独立开发库并按 [后端启动说明](backend/README.md#快速启动) 加载配置、启动服务。
3. 进入要开发的 `frontend/<应用>/`，执行 `npm ci` 和 `npm run dev`。构建用 `npm run build`。各应用的入口见 [前端组件](docs/README.md#前端组件)。

根目录的 [compose.yaml](compose.yaml) 启动本地基础设施。服务器使用 [compose.prod.yaml](compose.prod.yaml)，公共 HTTPS 模式叠加 [compose.public.yaml](compose.public.yaml)。连接已有服务器可直接从[三个管理页面的 SSH 入口](deploy/README.md#4-建立电脑访问隧道)开始。
