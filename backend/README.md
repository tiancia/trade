# Trade Backend

基于 Spring Boot 4 和 Java 21 的后端服务，包含 OKX 策略交易、Polymarket AI 决策、AI 小说生成、文字游戏、二手集市、微博与 X 发布以及后台任务编排。

全项目文档统一从 [文档总导航](../docs/README.md) 进入；机器通信和接手步骤见 [全局上手指南](../docs/PROJECT_GUIDE.md)。本页负责后端启动，业务职责和调用入口见 [模块目录](../docs/backend/MODULES.md)。

## 快速启动

前置条件：

- JDK 21；
- 可连接的 MySQL 数据库；
- Redis（可选，仅用于热行情缓存；未部署时关闭对应开关）；
- 在 `backend/` 目录运行命令，确保状态文件、小说输出目录和 Python 脚本的相对路径正确。

以下命令从仓库根目录执行；如果终端已经位于 `backend/`，跳过第一行：

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

`.env.example` 是环境变量清单，不会被 Spring Boot 自动加载。请把所需变量配置到当前 Shell、IDE Run Configuration 或部署环境中。默认数据库账号为 `root`、密码为空；实际环境应显式设置 `SPRING_DATASOURCE_*`。

管理 API 需要 HTTP Basic 认证，缺少 `TRADE_MANAGEMENT_AUTH_CREDENTIALS_FILE` 时返回 503；管理写请求另需 `X-Trade-CSRF: 1`。凭据创建、开发隔离与生产强制认证见 [运维手册](../docs/backend/OPERATIONS.md#后台任务操作)。

当前运行配置启用 OKX 自动 PAPER 交易，以及每三小时生成、经 Telegram 人工审核后发布的 X 工作流；OKX 真实下单、Polymarket 和小说生成保持关闭。首次在未知环境验收时，按 [运维手册](../docs/backend/OPERATIONS.md) 的安全启动覆盖值关闭自动任务。X 需要由运行环境提供目标账号、审核会话 / 审核人以及供应商凭据。

开发流程和测试命令见 [贡献指南](CONTRIBUTING.md)，AI 工具先读 [AGENTS.md](AGENTS.md)。其余架构、业务和观测专题见 [文档总导航](../docs/README.md)。

## 配置与安全开关

自动任务、业务模块与真实执行是不同门禁。OKX 新单必须同时满足 `execution-mode=live`、`live-enabled=true` 和资金安全条件；启动覆盖值、SPOT 执行范围及首次 LIVE 对账/恢复步骤统一见 [运维手册](../docs/backend/OPERATIONS.md#安全启动)。

新的 `live` 资金域以 `HALTED` 初始化；首次上线或迁移保持 `live-enabled=false`，先完成对账，再通过受保护的 resume 流程显式放行。

环境变量见 `.env.example`，密钥和本地状态不得提交。资金与非资金状态归属见 [模块目录](../docs/backend/MODULES.md)。

`trade.trading.event-queue.full-policy` 支持 `drop-oldest`、`drop-latest` 和 `block`。实时行情默认使用 `drop-oldest` 保留更新数据；选择 `block` 时，生产者最多等待 `publish-timeout-ms`，适合不允许静默丢弃且上游能够承受阻塞的场景。

## 数据库

结构、初始化和主键规范见 [数据库目录](src/main/resources/db/README.md)；已有表不会因重启自动升级，按 [旧库升级说明](src/main/resources/db/upgrade/README.md) 备份并手工选择补丁。
