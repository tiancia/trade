# AI 开发约定

本文件是 AI 编码助手在 `backend/` 范围内工作的首要仓库约束。它补充而不替代 [架构说明](docs/ARCHITECTURE.md) 和 [贡献指南](CONTRIBUTING.md)。若任务目录下以后出现更具体的 `AGENTS.md`，以离目标文件最近的规则为准。

## 项目事实

- 技术栈：Java 21、Spring Boot 4、Maven Wrapper、MyBatis、MySQL；Redis 只承担可关闭的热行情缓存。
- 架构形态：按业务域组织的模块化单体，域内统一先分 `interfaces`、`application`、`domain`、`infrastructure`，再按能力细分。
- 应用入口：`src/main/java/com/trade/TradeApplication.java`。
- 后台任务入口：`automation/application/task/AutomationTaskRegistrar.java`；`register()` 只登记任务，真正启动由 `ApplicationReadyEvent + auto-start` 或任务管理 API 触发。
- 危险能力默认关闭。不要为通过测试或方便调试而打开自动任务、真实下单或真实发布开关。

## 开始修改前

1. 运行 `git status --short`，保护已有未提交改动；不要重置、覆盖或顺手格式化无关文件。
2. 从 [模块目录](docs/MODULES.md) 定位所属业务域，再读该域的 `package-info.java` 和直接调用链。
3. 检查配置门禁、持久化契约、Mapper XML 和相邻测试，不要只根据类名猜测行为。
4. 需求存在多种解释时，优先选择能保持现有 API、数据库和资金安全语义的最小改动。

## 代码放置规则

| 内容 | 放置位置 |
| --- | --- |
| HTTP 映射、鉴权、请求响应转换 | `<domain>/interfaces/web` |
| 用例编排、事务边界 | `<domain>/application/<能力>`，小模块使用 `service` |
| 用例需要的出站接口 | `<domain>/application/port`，确有替换价值时再引入 |
| 纯业务值与规则 | `<domain>/domain/model` 或 `domain/<能力>` |
| 共享业务异常 | `<domain>/domain/exception` |
| MyBatis、Row、Repository/Store 实现 | `<domain>/infrastructure/persistence` |
| Broker、外部执行器实现 | `<domain>/infrastructure/broker`，契约放 `application/port` |
| 配置绑定、Bean 装配 | `<domain>/infrastructure/config` |
| Prompt、AI 响应解析与校验 | `<domain>/application/decision` |
| 定时触发 | `<domain>/interfaces/scheduler`，保持为薄入口 |
| 外部协议、签名、供应商 DTO | `client/<provider>` |
| 跨域任务启停和状态汇总 | `automation` |
| 真正无业务归属的纯函数 | `common/support` |

不要创建全局 `controller`、`service`、`mapper` 或泛化 `utils` 大目录。业务域之间不得直接 import；跨域生命周期由 `automation` 编排。`client`、`ai`、`common` 不得反向依赖业务域，`web` 不得直接暴露持久化 Row 或 Mapper。

领域模型不得反向依赖 application、interfaces 或 infrastructure；application/port 不得依赖实现。业务域根目录不再平铺 market、execution、persistence 等能力包。`client` 按供应商组织，`common` 保留纯函数，不强制创建空层。

## 数据库主键规范

- 所有新增或重建的业务表统一使用单列 `id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`，由数据库生成主键。
- UUID、账号、会话、令牌哈希、订单幂等键和复合业务键使用独立字段及 `UNIQUE` 约束；不得改用这些字段作为主键，必须保留已有接口、外键关联、去重、UPSERT、行锁与交易幂等语义。
- 普通业务 INSERT 不指定 `id`，不得用 `MAX(id)+1` 等应用计算替代数据库自增；确需返回新主键时使用 MyBatis 生成键回填，数字主键对应的 Java 字段使用 `Long`。初始化种子和存量迁移需要保留固定 ID 时可显式写入。
- 修改主键时同步更新模块 schema、旧库手工升级脚本、Mapper/Row 和相关测试。遵守 [数据库主键与重建说明](src/main/resources/db/README.md)；删除旧表重建仅适用于允许丢弃全部数据的开发库，已有表不会因重启自动升级。

## 高风险变更

- 交易：保持幂等键、确定性 `clOrdId`、订单状态机、乐观锁和审计历史的语义；重试不得直接二次下单。
- 行情：生产者统一发布到有界事件总线；保留背压、异常隔离、指标和优雅停机，不在 WebSocket 回调线程直接写数据库。
- 数据库：完整结构更新所属模块的 `db/schema/<module>/schema.sql`；存量库升级另增 `db/upgrade/<module>/` 手工脚本并更新升级说明。新增数据库模块时同步更新 `spring.sql.init.schema-locations`。Mapper 类型改包时同步更新 XML namespace/resultType/parameterType。
- 配置：新增配置时同步检查 `application.yml`、`.env.example`、`@ConfigurationProperties`、默认安全值和运维文档。
- 凭据：不得读取、打印或提交 `.env`、密钥、令牌、私钥和生产数据。

## 验证要求

先运行受影响范围的测试，交付前运行完整套件：

```powershell
.\mvnw.cmd -q "-Dtest=PackageArchitectureTest" test
.\mvnw.cmd clean test
```

在 PowerShell 中始终给完整 `-Dtest=...` 参数加引号。测试必须离线且不得发起真实下单、发布、付费 AI 请求或生产数据库写入；需要外部凭据的测试应显式跳过。

移动 Java 类型时必须同步移动测试并使用 `clean test`，防止 `target/` 中旧 class 掩盖遗漏。结构规则由 `src/test/java/com/trade/architecture/PackageArchitectureTest.java` 保护，不要通过放宽规则来掩盖分层问题。

## 交付说明

AI 完成任务时应说明：

- 改了什么以及为什么；
- 关键文件和行为变化；
- 运行过哪些验证、结果如何；
- 未验证的外部依赖、迁移步骤或风险；
- 是否保留了任务开始前已存在的未提交改动。

只修改文档也要检查链接、命令、路径和配置名是否与当前仓库一致。
