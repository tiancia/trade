# 项目文档总导航

项目现行架构、通信、接口、配置与运维文档统一放在本目录。组件根目录保留自己的 README，部署脚本和配置仍在 `deploy/`，数据库与内置资源的使用说明紧邻对应文件。

## 按目标开始阅读

| 现在要做什么 | 推荐顺序 |
| --- | --- |
| 第一次接手整个项目 | [项目 README](../README.md) → [架构与部署上手指南](PROJECT_GUIDE.md) |
| 连接已运行的驾驶舱 / X 控制台 | [上手指南](PROJECT_GUIDE.md)第 7 节：SSH、网关认证与只读验收 |
| 开发后端 | [后端 README](../backend/README.md) → [模块目录](backend/MODULES.md) → [架构说明](backend/ARCHITECTURE.md) → [贡献指南](../backend/CONTRIBUTING.md) |
| 开发前端 | [交易驾驶舱 README](../frontend/trading-cockpit/README.md) 或 [X 控制台 README](../frontend/x-console/README.md) |
| 部署、更新或回滚服务器 | [生产部署说明](../deploy/README.md)，配合 [上手指南](PROJECT_GUIDE.md)中的网络和配置说明 |
| 日常运行与故障排查 | [后端运维手册](backend/OPERATIONS.md)；基础设施问题看 [部署说明](../deploy/README.md) |
| 修改数据库 | [SQL 目录说明](../backend/src/main/resources/db/README.md) → [旧库升级说明](../backend/src/main/resources/db/upgrade/README.md) |

仓库目录见 [项目 README](../README.md)。Maven 命令从 `backend/` 执行，生产 Compose 命令从仓库根目录（服务器通常为 `/opt/trade`）执行；具体字段、方法和默认值以当前代码、Properties 类及测试为准。

## 文档清单

### 整体架构、环境和运维

| 文档 | 回答什么 |
| --- | --- |
| [项目 README](../README.md) | 项目有哪些部分，从哪里开始 |
| [架构与部署上手指南](PROJECT_GUIDE.md) | 电脑、国内主机、德国 VPS 如何通信；端口、令牌、核心业务与接入步骤 |
| [生产部署说明](../deploy/README.md) | Docker、运行配置、密钥权限、镜像、发布、回滚、备份和公共模式 |
| [本地基础设施](../backend/infrastructure/README.md) | 开发用 MySQL、Redis、Prometheus、Grafana 的启动方式 |
| [后端运维](backend/OPERATIONS.md) | 应用安全启动、任务控制、资金停止、恢复和业务排障 |
| [可观测性](backend/OBSERVABILITY.md) | 指标流、抓取配置、看板、告警及本地/生产差异 |

### 后端设计与开发

| 文档 | 回答什么 |
| --- | --- |
| [后端 README](../backend/README.md) | 后端定位、启动、测试与专题入口 |
| [模块目录](backend/MODULES.md) | 一个功能属于哪个业务域，从哪个类进入 |
| [架构说明](backend/ARCHITECTURE.md) | 四层职责、依赖方向和典型调用链 |
| [贡献指南](../backend/CONTRIBUTING.md) | 开发、数据库变更、测试与评审约定 |
| [AI 开发约定](../backend/AGENTS.md) | AI 工具修改后端时必须遵守的仓库规则 |

### 业务、接口和数据

| 文档 | 回答什么 |
| --- | --- |
| [Trading 回测](backend/TRADING_BACKTEST.md) | 回测参数、成交、手续费、滑点和指标 |
| [Trading 模拟资金与策略配置](backend/TRADING_SIMULATION.md) | 策略参数版本、独立模拟资金、成交与估值账本及 API |
| [X 工作流](backend/X_WORKFLOW.md) | AI 草稿、内容配置、Telegram 审核、发布与 UNKNOWN |
| [X API](backend/X_API.md) | X 客户端方法、OAuth 凭据和协议边界 |
| [Telegram API](backend/TELEGRAM_API.md) | Bot 客户端、消息、更新轮询和审核接入 |
| [微博工作流](backend/WEIBO_WORKFLOW.md) | 微博热点、生成、审核与发布门禁 |
| [数据库 SQL 目录](../backend/src/main/resources/db/README.md) | 完整 schema、数据归属、初始化与主键规则 |
| [数据库升级](../backend/src/main/resources/db/upgrade/README.md) | 旧库手工补丁、适用条件与升级顺序 |
| [内置文字游戏剧情](../backend/src/main/resources/textgame/stories/README.md) | 剧情 JSON 的校验、显式导入和版本发布 |
| [Polymarket 工具](../tools/polymarket/README.md) | Python 桥接和调试脚本的用途与输入输出 |

### 前端组件

| 组件 | 入口与范围 |
| --- | --- |
| 交易驾驶舱 | [README](../frontend/trading-cockpit/README.md)：本地开发、SSE、策略操作、回测及 Node SSR |
| X 控制台 | [README](../frontend/x-console/README.md)：只读列表、详情、状态与网关访问 |
| 文字游戏 | 尚无独立 README；开发脚本见 [package.json](../frontend/text-game/package.json)，后端归属见模块目录 |
| 集市 | 尚无独立 README；开发脚本见 [package.json](../frontend/market/package.json)，后端归属见模块目录 |

## 维护规则

- 全局机器、通信和接手路径更新 `PROJECT_GUIDE.md`；代码分层与业务专题更新 `docs/backend/` 对应文档。
- 部署命令、目录权限、配置模板和脚本行为更新 `deploy/README.md`，避免在多份文档复制整套安装步骤。
- README 保留组件定位、启动和导航；SQL、资源格式说明与文件就近维护。
- 修改默认值、代理、端口或安全门禁时核对源码、配置模板及文档；区分属性类默认值、配置回退值与服务器实际环境。
- 仓库只保留现行架构、API、配置、使用说明和运维手册。计划、排查、修复、审查、验收与架构决策历史记录统一放在桌面 `0/trade-archive/`，按原仓库相对路径组织，不在仓库新增过程文档。
- 当前行为发生变化时直接更新对应现行说明，不累加任务经过或历史验收结果。移动文档同步更新入站和出站链接；示例只写字段名或虚构值。
