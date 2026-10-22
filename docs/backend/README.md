# 后端文档导航

[返回项目文档总导航](../README.md) · [全局架构与接手指南](../PROJECT_GUIDE.md) · [生产部署说明](../../deploy/README.md)

本目录集中保存后端现行架构、业务、接口、配置与运维专题，路径为仓库根目录的 `docs/backend/`。具体字段、方法签名与默认值以当前代码和测试为准。文档迁移不改变运行目录：Maven 命令仍在 `backend/` 执行，生产部署命令按部署说明从仓库根目录执行。

## 按阅读目标选择

### 第一次接触后端

1. [后端 README](../../backend/README.md)：能力范围、快速启动和安全开关；
2. [模块目录](MODULES.md)：每个模块的职责、入口、配置和数据归属；
3. [架构说明](ARCHITECTURE.md)：标准分层、依赖方向和典型调用链；
4. [贡献指南](../../backend/CONTRIBUTING.md)：开发流程、测试门禁和文档更新规则。

### 开发或评审代码

- [架构说明](ARCHITECTURE.md)：判断代码应该放在哪里；
- [模块目录](MODULES.md)：快速定位调用链和测试；
- [Trading 回测说明](TRADING_BACKTEST.md)：回测请求、成交模型和指标口径；
- [Trading 模拟资金与策略配置](TRADING_SIMULATION.md)：参数版本、模拟资金会话、成交与估值账本及 API；
- [Telegram Bot API](TELEGRAM_API.md)：客户端方法、显式实例化、更新和失败处理；
- [微博审核工作流](WEIBO_WORKFLOW.md)：热点、AI、审核门禁、Telegram 投递、回调认证与发布资格；
- [X 客户端](X_API.md)：OAuth 1.0a 四凭据、账号查询与文本发布协议；
- [X 审核工作流](X_WORKFLOW.md)：配置内容方向、语气和长度，Telegram 审核及审核后发布；
- [AI 开发约定](../../backend/AGENTS.md)：AI 编码助手必须遵守的边界与交付格式。

Telegram 协议和共享客户端归 `client/telegram`，微博和 X 分别拥有各自的审核状态、投递与回调适配器；当前依赖边界见架构说明和模块目录。

### 部署、值守或排障

- [全局架构与接手指南](../PROJECT_GUIDE.md)：三台机器、三条通信链路、端口、令牌与接手验收；
- [生产部署说明](../../deploy/README.md)：Ubuntu Docker、配置、构建、发布、回滚与备份；
- [本地基础设施](../../backend/infrastructure/README.md)：开发环境的 MySQL、Redis 和监控；
- [运维手册](OPERATIONS.md)：安全启动、任务启停、观测、停机和故障处理；
- [Trading 可观测性](OBSERVABILITY.md)：Prometheus 抓取、Grafana 仪表盘、告警规则和排障；
- [数据库目录](../../backend/src/main/resources/db/README.md)：模块 schema 索引与启动执行范围；
- [数据库升级说明](../../backend/src/main/resources/db/upgrade/README.md)：手工补丁、前置依赖与历史脚本重叠；
- `.env.example`：环境变量清单，不包含真实凭据；
- `application.yml`：应用运行配置默认值；显式创建的客户端属性默认值以对应 Properties 类为准。

## 文档责任边界

| 文档 | 负责回答 | 不负责保存 |
| --- | --- | --- |
| `backend/README.md` | 后端是什么、如何首次运行 | 全项目通信图、完整生产部署步骤 |
| `docs/README.md`、`docs/PROJECT_GUIDE.md` | 总导航、系统全貌与接手路径 | 每个业务流程的全部配置细节 |
| `deploy/README.md` | 生产基础设施、配置与发布步骤 | 业务规则、密钥及实时环境状态 |
| `ARCHITECTURE.md` | 代码如何分层、允许哪些依赖 | 某个 API 的临时实现细节 |
| `MODULES.md` | 能力属于哪个模块、从哪里进入 | 每个类的逐项清单 |
| `OPERATIONS.md` | 如何安全启动、观测、停止和排障 | 密钥或环境专属地址 |
| `CONTRIBUTING.md` | 如何提交可维护的变更 | 架构决策历史 |
| `AGENTS.md` | AI 修改仓库时的强约束 | 产品需求和未确认推测 |

新增文档前先判断是否能更新现有事实来源，避免同一配置或流程在多处复制后发生漂移。仓库只保留现行架构、API、配置、使用说明和运维手册；计划、排查、修复、审查、验收与架构决策历史记录统一放在桌面 `0/trade-archive/`，按原仓库相对路径组织，不在仓库新增过程文档。
