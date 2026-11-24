# 项目文档总导航

仓库结构和本地上手步骤见[项目首页](../README.md)。

## 开发与运维

| 要做的事 | 入口 |
| --- | --- |
| 启动本地环境 | [MySQL、Redis 和监控](../backend/infrastructure/README.md) · [后端启动](../backend/README.md) |
| 按功能找代码、理解调用链 | [后端模块目录](backend/ARCHITECTURE.md#模块目录) · [OKX 交易调用链](backend/ARCHITECTURE.md#okx-交易调用链) |
| 修改和验证后端 | [贡献指南](../backend/CONTRIBUTING.md) · [AI 开发约定](../backend/AGENTS.md) |
| 查表或升级数据库 | [完整结构与 Flyway 迁移](../backend/src/main/resources/db/README.md) · [接入 Flyway 前的历史补丁](../backend/src/main/resources/db/upgrade/README.md) |
| 访问或维护服务器 | [部署拓扑](../deploy/README.md#部署方案) · [SSH 访问](../deploy/README.md#4-建立电脑访问隧道) · [发布与回滚](../deploy/README.md#后续更新和回滚) · [数据与备份](../deploy/README.md#数据备份和监控) |
| 启停任务、恢复交易或排查告警 | [后端运维](backend/OPERATIONS.md) · [指标、看板和告警](backend/OBSERVABILITY.md) |

## 业务专题

| 功能 | 入口 |
| --- | --- |
| OKX 交易 | [模拟资金与策略配置](trading/SIMULATION.md) · [历史回测](trading/BACKTEST.md) · [Trading AI 策略顾问](trading/AI.md) |
| 内容生成、审核与发布 | [X 工作流](publishing/X_WORKFLOW.md) · [微博工作流](publishing/WEIBO_WORKFLOW.md) |
| 平台客户端 | [X API 与 OAuth](publishing/X_API.md) · [Telegram Bot API](publishing/TELEGRAM_API.md) |
| Polymarket | [Python 桥接、输入输出与调试](../tools/polymarket/README.md) |

## 前端组件

| 应用 | 用途 | 本地开发入口 |
| --- | --- | --- |
| 交易驾驶舱 | 行情、策略、模拟资金和回测 | [trading-cockpit](../frontend/trading-cockpit/README.md) |
| X 控制台 | 查看草稿、审核和发布结果，临时启停 X 任务 | [x-console](../frontend/x-console/README.md) |
| 功能控制台 | 管理各模块开关、查看任务状态 | [control-console](../frontend/control-console/README.md) |

## 维护规则

每个主题维护一份正文。组件启动方法放在组件目录，跨组件调用、架构和业务说明放在对应专题。行为变更时核对命令、路径和配置，文件移动时同步更新导航及引用。

Agent 生成的操作记录、命令日志、开发计划、排查与验收记录、截图和一次性脚本直接写入 `A:\agent-artifacts\trade\<YYYY-MM-DD>\<任务名-HHmmss或唯一后缀>\`。按新加坡时间为每项任务新建独立目录。
