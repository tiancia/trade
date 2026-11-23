# Trade

Trade 用一个 Spring Boot 后端承载 OKX 策略交易、Polymarket、小说生成、文字游戏、二手集市和社交内容发布。五个前端各自构建，通过 HTTP API 访问后端。第一次读代码，可以从 [应用入口](backend/src/main/java/com/trade/TradeApplication.java) 和 [业务模块目录](docs/backend/MODULES.md) 开始。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| [backend/](backend/README.md) | Java 21、Spring Boot 4、MyBatis。业务代码按域组织，SQL、迁移和 Mapper 在 `src/main/resources/` |
| [frontend/](frontend/) | 生产部署交易驾驶舱、X 控制台和功能控制台。文字游戏与集市也各有独立前端 |
| [tools/polymarket/](tools/polymarket/README.md) | 后端调用的 Python 下单桥接、调试工具和样例 |
| [deploy/](deploy/README.md) | 生产配置模板、网关、代理、镜像构建、发布和回滚脚本 |
| [.github/workflows/](.github/workflows/cnb-build-deploy.yml) | 手动提交 CNB 构建或发布任务的入口 |
| [docs/](docs/README.md) | 系统上手指南、后端架构与运维、交易和内容发布专题 |

小说正文、配图和分类表放在仓库同级的 `story/`。在本机对应 `A:\story`，可用 `TRADE_STORY_OUTPUT_DIR` 指定其他生成目录。

## 本地上手

1. 在 Windows 仓库根目录准备本地配置，启动 MySQL 和 Redis。监控服务和数据位置见 [本地基础设施](backend/infrastructure/README.md)。

   ```powershell
   if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
   docker compose up -d mysql redis
   ```

2. 安装 JDK 21，进入 `backend/`，使用独立开发库并按 [后端启动说明](backend/README.md#快速启动) 加载配置、启动服务。
3. 进入要开发的 `frontend/<应用>/`，执行 `npm ci` 和 `npm run dev`。构建用 `npm run build`。各应用的入口见 [前端组件](docs/README.md#前端组件)。

连接现有服务器时，从 [系统与通信指南](docs/PROJECT_GUIDE.md) 了解三个管理页面的访问方式。Ubuntu 部署、备份和回滚命令集中在 [生产部署说明](deploy/README.md)。
