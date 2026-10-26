# 项目文档总导航

项目目录见 [项目 README](../README.md)，初次接手先读 [系统与通信指南](PROJECT_GUIDE.md)。下表同时作为阅读入口和各主题的正文归属表；组件、数据库与内置资源的说明保留在对应目录。

Maven 命令从 `backend/` 执行，生产 Compose 命令从仓库根目录执行；具体字段和默认值以当前代码、配置及测试为准。

### 整体架构、环境和运维

| 文档 | 回答什么 |
| --- | --- |
| [系统与通信指南](PROJECT_GUIDE.md) | 机器分工、通信、端口、核心业务链路与接手入口 |
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

- **一个主题一份正文**：按上表确定归属；其他文档保留必要摘要并使用相对链接，不复制完整规则、配置表或操作步骤。
- **优先更新已有文档**：新增文件须有独立、长期的读者和职责，且现有文件无法合理承载；新增后补入本导航。
- **入口保持简短**：组件 README 保留定位、最短启动步骤和导航；系统指南解释全貌，部署、运维及业务细节由对应手册维护。
- **改行为就改所属文档**：核对源码、命令、路径及配置名，区分默认值、配置模板与服务器实际状态；示例不包含真实凭据。
- **合并删除同步引用**：保留独有内容，更新入站、出站链接及本导航；检查文件路径和章节锚点，避免仅用章节编号指引。
