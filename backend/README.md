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

管理 API 默认要求 HTTP Basic 认证：用 `htpasswd -cB` 创建个人 bcrypt 凭据文件，并设置 `TRADE_MANAGEMENT_AUTH_CREDENTIALS_FILE` 为其绝对路径；未配置时管理 API 返回 503。原生本机开发若显式关闭 `TRADE_MANAGEMENT_AUTH_ENABLED`，须限制为隔离环境。生产 Compose 强制开启认证，复用只读挂载的网关凭据文件。管理写请求仍需 `X-Trade-CSRF: 1`，详见[运维手册](../docs/backend/OPERATIONS.md)。

当前运行配置启用 OKX 自动 PAPER 交易，以及每三小时生成、经 Telegram 人工审核后发布的 X 工作流；OKX 真实下单、Polymarket 和小说生成保持关闭。首次在未知环境验收时，按 [运维手册](../docs/backend/OPERATIONS.md) 的安全启动覆盖值关闭自动任务。X 需要由运行环境提供目标账号、审核会话 / 审核人以及供应商凭据。

## 开发与运维入口

| 任务 | 文档 |
| --- | --- |
| 定位业务域、入口与配置归属 | [模块目录](../docs/backend/MODULES.md) |
| 了解四层结构、依赖与资金账本调用链 | [架构说明](../docs/backend/ARCHITECTURE.md) |
| 修改代码与执行测试 | [贡献指南](CONTRIBUTING.md)；AI 工具先读 [AGENTS.md](AGENTS.md) |
| 启停任务、资金停止、恢复与排障 | [运维手册](../docs/backend/OPERATIONS.md) |
| 监控、指标和告警 | [可观测性](../docs/backend/OBSERVABILITY.md) |
| 业务 API、回测、模拟资金与审核流程 | [业务专题清单](../docs/README.md#业务接口和数据) |

## 配置与安全开关

后台能力通常有三层开关，含义不同：

1. `trade.automation.<task>.auto-start`：应用启动后是否自动启动循环；
2. `trade.trading.enabled`、`trade.polymarket.enabled`、`trade.story.enabled`：业务模块是否执行；
3. 真实资金开关：OKX 必须同时满足 `execution-mode=live` 和 `live-enabled=true`，Polymarket 必须设置 `execution.enabled=true`。

只打开自动启动并不等于允许真实下单；真实资金开关也不应在缺少风控参数、API 权限或地域检查时启用。

新的 `live` 资金域会以 `HALTED` 初始化。首次上线或迁移后应保持
`live-enabled=false`，完成一次成功对账，再通过受保护的 resume 流程显式放行。
当前 LIVE 资金结算仅开放 SPOT 市价单；衍生品保留只读恢复能力，但新单会安全阻断。

常用环境变量示例见 `.env.example`。密钥、令牌和运行时状态不得提交到 Git；`data/trading-state.json` 只保存非资金策略记忆，仓位、成本、风险和资金停止状态保存于 MySQL，仓库只保留示例文件。

`trade.trading.event-queue.full-policy` 支持 `drop-oldest`、`drop-latest` 和 `block`。实时行情默认使用 `drop-oldest` 保留更新数据；选择 `block` 时，生产者最多等待 `publish-timeout-ms`，适合不允许静默丢弃且上游能够承受阻塞的场景。

## 数据库

SQL 按业务模块分目录：`db/schema/<module>/schema.sql` 保存完整结构，`db/upgrade/<module>/` 保存旧数据库的升级补丁。模块索引见 [数据库目录](src/main/resources/db/README.md)。

应用启动时按 `spring.sql.init.schema-locations` 中的显式顺序执行各模块 schema。`CREATE TABLE IF NOT EXISTS` 会创建缺失的表，但不会给已有表补字段或修改索引。

`db/upgrade/` 下的脚本需要手工执行，项目不使用 Flyway 或 Liquibase。升级已有数据库前请先备份，再根据当前表结构选择脚本；具体约定见 [升级说明](src/main/resources/db/upgrade/README.md)。

## 测试

```powershell
.\mvnw.cmd clean test
```

单元测试和集成测试应放在与生产代码一致的包路径中。例如修改 `com.trade.weibo.application.service` 时，相应测试应位于 `src/test/java/com/trade/weibo/application/service/`。

默认测试套件不会真实下单；需要外部凭据的 OKX 集成测试会按条件跳过。
