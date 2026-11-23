# 项目文档总导航

本地启动和仓库目录见 [项目首页](../README.md)。连接现有环境从 [系统与通信指南](PROJECT_GUIDE.md) 开始，按功能找代码从 [后端模块目录](backend/MODULES.md) 开始。

## 开发与运维

| 要做的事 | 入口 |
| --- | --- |
| 启动本地环境 | [MySQL、Redis 和监控](../backend/infrastructure/README.md) · [后端启动](../backend/README.md) |
| 理解分层与调用链 | [后端架构](backend/ARCHITECTURE.md) · [业务模块与代码入口](backend/MODULES.md) |
| 修改和验证后端 | [贡献指南](../backend/CONTRIBUTING.md) · [AI 开发约定](../backend/AGENTS.md) |
| 查表或升级数据库 | [完整结构与 Flyway 迁移](../backend/src/main/resources/db/README.md) · [接入 Flyway 前的历史补丁](../backend/src/main/resources/db/upgrade/README.md) |
| 发布、回滚或恢复备份 | [生产部署说明](../deploy/README.md) |
| 启停任务、恢复交易或排查告警 | [后端运维](backend/OPERATIONS.md) · [指标、看板和告警](backend/OBSERVABILITY.md) |

## 业务专题

| 功能 | 入口 |
| --- | --- |
| OKX 交易 | [模拟资金与策略配置](trading/SIMULATION.md) · [历史回测](trading/BACKTEST.md) · [Trading AI 策略顾问](trading/AI.md) |
| 内容生成、审核与发布 | [X 工作流](publishing/X_WORKFLOW.md) · [微博工作流](publishing/WEIBO_WORKFLOW.md) |
| 平台客户端 | [X API 与 OAuth](publishing/X_API.md) · [Telegram Bot API](publishing/TELEGRAM_API.md) |
| Polymarket | [Python 桥接、输入输出与调试](../tools/polymarket/README.md) |
| 文字游戏 | [内置剧情、导入与版本发布](../backend/src/main/resources/textgame/stories/README.md) |

## 前端组件

| 应用 | 用途 | 本地开发入口 |
| --- | --- | --- |
| 交易驾驶舱 | 行情、策略、模拟资金和回测 | [trading-cockpit](../frontend/trading-cockpit/README.md) |
| X 控制台 | 查看草稿、审核与发布状态 | [x-console](../frontend/x-console/README.md) |
| 功能控制台 | 管理各模块开关、查看任务状态 | [control-console](../frontend/control-console/README.md) |
| 文字游戏 | 剧情选择和会话 | [text-game](../frontend/text-game/package.json) |
| 二手集市 | 商品、图片和会话 | [market](../frontend/market/package.json) |

## 维护规则

每个主题维护一份正文。组件启动方法放在组件目录，跨组件调用、架构和业务说明放在对应专题。行为变更时核对命令、路径和配置，文件移动时同步更新导航及引用。操作日志、开发计划和一次性验收记录放在仓库外。
