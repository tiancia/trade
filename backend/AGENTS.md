# AI 开发约定

本文件约束 AI 编码助手在 `backend/` 范围内的工作。开发流程与验证要求见 [贡献指南](CONTRIBUTING.md)，更具体的目录规则以最近的 `AGENTS.md` 为准。

## 项目事实

- 技术栈：Java 21、Spring Boot 4、Maven Wrapper、MyBatis、MySQL；Redis 只承担可关闭的热行情缓存。
- 架构形态：按业务域组织的模块化单体，域内统一先分 `interfaces`、`application`、`domain`、`infrastructure`，再按能力细分；出站契约放在模块根目录的 `port`，与四层目录并列。
- 应用入口：`src/main/java/com/trade/TradeApplication.java`。
- 后台任务入口：`automation/application/task/AutomationTaskRegistrar.java`；`register()` 只登记任务，真正启动由 `ApplicationReadyEvent + auto-start` 或任务管理 API 触发。
- 不得为通过测试或方便调试而打开自动任务、真实下单或真实发布开关。配置类默认值与当前运行模板不同，启动前核对 [运维手册](../docs/backend/OPERATIONS.md)。

## 开始修改前

1. 运行 `git status --short`，保护已有未提交改动；不要重置、覆盖或顺手格式化无关文件。
2. 从 [模块目录](../docs/backend/MODULES.md) 定位所属业务域，再读该域的 `package-info.java` 和直接调用链。
3. 检查配置门禁、持久化契约、Mapper XML 和相邻测试，不要只根据类名猜测行为。
4. 需求存在多种解释时，优先选择能保持现有 API、数据库和资金安全语义的最小改动。

## 结构与数据规则

- 类型放置、四层依赖与业务域边界遵守 [架构说明](../docs/backend/ARCHITECTURE.md)，不要新增全局技术类型目录或空层。
- 主键、业务唯一约束、MyBatis 回填及重建边界遵守 [数据库开发规范](src/main/resources/db/README.md#主键开发规范)；新增表仍必须使用自增 BIGINT `id` 单列主键，不得破坏已有业务键和幂等语义。

## 高风险变更

- 交易：保持幂等键、确定性 `clOrdId`、订单状态机、乐观锁和审计历史的语义；重试不得直接二次下单。
- 行情：生产者统一发布到有界事件总线；保留背压、异常隔离、指标和优雅停机，不在 WebSocket 回调线程直接写数据库。
- 数据库：完整结构更新所属模块的 `db/schema/<module>/schema.sql`；运行时升级新增全局递增的 `db/migration/V<N>__description.sql`，同步提升 Dockerfile 的 `com.trade.database.version`。已发布迁移（包括 Java 迁移及支持 SQL）不可修改，只能追加新版本。`db/upgrade/` 只保留接入 Flyway 前的历史补丁，不扫描执行。新增模块同步更新完整结构列表与迁移。Mapper 类型改包时同步更新 XML namespace/resultType/parameterType。
- 配置：新增配置时同步检查 `application.yml`、`.env.example`、`@ConfigurationProperties`、默认安全值和运维文档。
- 凭据：不得读取、打印或提交 `.env`、密钥、令牌、私钥和生产数据。

文档归属与更新规则见 [文档总导航](../docs/README.md#维护规则)。

## 验证要求

按 [贡献指南的质量门禁](CONTRIBUTING.md#测试与质量门禁) 先运行受影响测试，交付前运行完整 `clean test`。PowerShell 中给完整 `-Dtest=...` 参数加引号；测试必须离线，不得真实下单、发布、消耗付费 AI 请求或写入生产数据库，缺少外部凭据时显式跳过。

移动 Java 类型时必须同步移动测试并使用 `clean test`，防止 `target/` 中旧 class 掩盖遗漏。结构规则由 `src/test/java/com/trade/architecture/PackageArchitectureTest.java` 保护，不要通过放宽规则来掩盖分层问题。

## 交付说明

AI 完成任务时应说明：

- 改了什么以及为什么；
- 关键文件和行为变化；
- 运行过哪些验证、结果如何；
- 未验证的外部依赖、迁移步骤或风险；
- 是否保留了任务开始前已存在的未提交改动。

只修改文档也要检查链接、命令、路径和配置名是否与当前仓库一致。
