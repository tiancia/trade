# 项目文档总导航

项目的架构、通信和后端专题文档统一放在本目录。组件根目录保留自己的 README，部署脚本和配置仍在 `deploy/`，数据库与内置资源的使用说明紧邻对应文件。

## 按目标开始阅读

| 现在要做什么 | 推荐顺序 |
| --- | --- |
| 第一次接手整个项目 | [项目 README](../README.md) → [架构与部署上手指南](PROJECT_GUIDE.md) |
| 连接已运行的驾驶舱 / X 控制台 | [上手指南](PROJECT_GUIDE.md)第 7 节：SSH、网关、应用令牌与只读验收 |
| 开发后端 | [后端 README](../backend/README.md) → [模块目录](backend/MODULES.md) → [架构说明](backend/ARCHITECTURE.md) → [贡献指南](../backend/CONTRIBUTING.md) |
| 开发前端 | [交易驾驶舱 README](../frontend/trading-cockpit/README.md) 或 [X 控制台 README](../frontend/x-console/README.md) |
| 部署、更新或回滚服务器 | [生产部署说明](../deploy/README.md)，配合 [上手指南](PROJECT_GUIDE.md)中的网络和配置说明 |
| 日常运行与故障排查 | [后端运维手册](backend/OPERATIONS.md)；基础设施问题看 [部署说明](../deploy/README.md) |
| 修改数据库 | [SQL 目录说明](../backend/src/main/resources/db/README.md) → [旧库升级说明](../backend/src/main/resources/db/upgrade/README.md) |

## 目录怎么划分

```text
trade/
├─ README.md                         项目介绍与入口
├─ docs/
│  ├─ README.md                      本页：全项目文档总导航
│  ├─ PROJECT_GUIDE.md               三台机器、通信、总体架构和接手步骤
│  └─ backend/
│     ├─ README.md                   后端专题导航
│     ├─ ARCHITECTURE.md             代码分层与依赖
│     ├─ MODULES.md                  模块职责和调用入口
│     ├─ OPERATIONS.md               应用启停、门禁和业务排障
│     ├─ OBSERVABILITY.md            指标、看板和告警
│     ├─ *_API.md / *_WORKFLOW.md    外部协议与业务流程
│     ├─ TRADING_BACKTEST.md         回测接口及统计口径
│     └─ adr/                       架构决策历史
├─ deploy/
│  ├─ README.md                      生产部署步骤与目录说明
│  ├─ scripts/                       构建、配置同步、发布和回滚脚本
│  ├─ egress/                        德国 VPS 出站隧道容器
│  ├─ nginx/                         网关与 API 转发配置
│  ├─ prometheus/                    生产监控抓取配置
│  └─ *.example                      运行配置模板
├─ backend/
│  ├─ README.md / CONTRIBUTING.md     后端启动和开发约定
│  ├─ AGENTS.md                      AI 修改 backend 的约束
│  ├─ infrastructure/README.md       本地中间件使用说明
│  └─ src/main/resources/db/         SQL 文件及就近维护说明
└─ frontend/<应用>/README.md          各前端组件的使用说明
```

`deploy` 是 deployment 的缩写：它提供“怎么把项目运行到服务器上”的配置和工具，不是另一套业务后端。文档路径调整不改变源码、容器服务名或命令工作目录；Maven 命令仍从 `backend/` 执行，生产 Compose 命令从仓库根目录（服务器通常为 `/opt/trade`）执行。

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
| [后端 README](../backend/README.md) | 后端能力、启动、测试与源码布局 |
| [后端文档导航](backend/README.md) | 后端专题的阅读路线与职责边界 |
| [模块目录](backend/MODULES.md) | 一个功能属于哪个业务域，从哪个类进入 |
| [架构说明](backend/ARCHITECTURE.md) | 四层职责、依赖方向和典型调用链 |
| [贡献指南](../backend/CONTRIBUTING.md) | 开发、数据库变更、测试与评审约定 |
| [AI 开发约定](../backend/AGENTS.md) | AI 工具修改后端时必须遵守的仓库规则 |

### 业务、接口和数据

| 文档 | 回答什么 |
| --- | --- |
| [Trading 回测](backend/TRADING_BACKTEST.md) | 回测参数、成交、手续费、滑点和指标 |
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
| X 控制台 | [README](../frontend/x-console/README.md)：只读列表、详情、状态与管理令牌 |
| 文字游戏 | 尚无独立 README；开发脚本见 [package.json](../frontend/text-game/package.json)，后端归属见模块目录 |
| 集市 | 尚无独立 README；开发脚本见 [package.json](../frontend/market/package.json)，后端归属见模块目录 |

### 架构决策历史

| 记录 | 状态 |
| --- | --- |
| [ADR-0001：模块化单体](backend/adr/0001-domain-first-modular-monolith.md) | 已接受：为什么按业务域组织，暂不拆微服务 |
| [ADR-0002：域内四层](backend/adr/0002-four-layer-module-layout.md) | 已接受：四层后再按能力细分 |
| [ADR-0003：共享人工审核](backend/adr/0003-shared-human-review-capability.md) | 已撤销的历史决策；当前布局以架构说明与工作流为准 |

## 维护规则

- 全局机器、通信和接手路径更新 `PROJECT_GUIDE.md`；代码分层与业务专题更新 `docs/backend/` 对应文档。
- 部署命令、目录权限、配置模板和脚本行为更新 `deploy/README.md`，避免在多份文档复制整套安装步骤。
- README 保留组件定位、启动和导航；SQL、资源格式说明与文件就近维护。
- 修改默认值、代理、端口或安全门禁时核对源码、配置模板及文档；区分属性类默认值、配置回退值与服务器实际环境。
- ADR 保留历史状态，不把已撤销决策当作现行结构。移动文档同步更新入站和出站链接；示例只写字段名或虚构值。
