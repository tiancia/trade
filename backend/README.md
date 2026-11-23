# Trade Backend

后端是 Java 21、Spring Boot 4 和 MyBatis 构建的模块化单体。应用从 `TradeApplication` 启动，HTTP 接口、后台任务和数据库访问按业务域组织。[应用入口](src/main/java/com/trade/TradeApplication.java) · [业务模块目录](../docs/backend/MODULES.md)

## 快速启动

1. 安装 JDK 21，准备独立开发用的 MySQL 数据库。仓库的本地 Compose 同时提供 Redis 热行情缓存。若单独运行 MySQL，可设置 `TRADE_TRADING_HOT_MARKET_CACHE_ENABLED=false`。[本地基础设施](infrastructure/README.md)
2. 在当前 PowerShell 或 IDE 中设置 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 和启用的 Redis 连接参数，值与开发库匹配。Spring Boot 读取进程环境，`.env.example` 供查阅。[后端配置示例](.env.example)
3. 从仓库根目录进入 `backend/`，按 [安全启动](../docs/backend/OPERATIONS.md#安全启动) 设置模块开关并运行 `.\mvnw.cmd spring-boot:run`。首次使用新库可按该节关闭后台任务。复用已有库时，功能控制台保存的选择优先于环境默认值。
4. 检查 `GET /actuator/health` 和实际模块状态。管理 API 使用操作员 Basic 认证，管理写入还需要 `X-Trade-CSRF: 1`。凭据文件由 `TRADE_MANAGEMENT_AUTH_CREDENTIALS_FILE` 指定。[后台任务操作](../docs/backend/OPERATIONS.md#后台任务操作)

后端使用当前工作目录解析相对路径。从 `backend/` 启动时，小说生成器读写 `../../story`，即仓库旁的 `story/`。从其他位置启动时，可以给 `TRADE_STORY_OUTPUT_DIR` 设置绝对路径。

## 数据库与交易配置

启动时先由 Flyway 校验版本并应用待执行迁移，完成后再恢复模块和启动任务。接入 Flyway 前的旧库须通过结构核验，历史手工补丁按适用条件查阅。[数据库结构与迁移](src/main/resources/db/README.md) · [旧库接入说明](src/main/resources/db/upgrade/README.md)

运行模板默认选择 OKX PAPER。LIVE 下单还需独立授权，新的 LIVE 资金域从 `HALTED` 开始，完成对账并显式恢复后才可放行。[功能启用](../docs/backend/OPERATIONS.md#功能启用) · [资金级停止与恢复](../docs/backend/OPERATIONS.md#资金级停止与恢复)

架构和依赖方向见 [架构说明](../docs/backend/ARCHITECTURE.md)。按改动范围选择测试，命令见 [贡献指南](CONTRIBUTING.md#测试与质量门禁)。AI 编码助手的约定见 [AGENTS.md](AGENTS.md)。
