# 贡献指南

本页负责后端开发流程与质量门禁。模块归属见 [模块目录](../docs/backend/MODULES.md)，结构规范见 [架构说明](../docs/backend/ARCHITECTURE.md)，Maven 命令从 `backend/` 执行。

## 开发流程

1. 从最新代码创建短生命周期分支，保持一次变更只解决一个主题。
2. 用 `git status --short` 识别并保护已有工作区改动。
3. 在 [模块目录](../docs/backend/MODULES.md) 中确认归属、入口和依赖边界。
4. 先补测试或明确验收条件，再实现最小完整改动。
5. 更新与行为变化直接相关的配置、数据库、接口和运维文档。
6. 按影响范围完成必要验证，再提交评审；不默认追加全量测试。

## 目录与依赖

新功能进入所属业务域，目录、类型放置和依赖方向以 [架构说明](../docs/backend/ARCHITECTURE.md) 为准。只有至少两个业务域已经稳定复用的纯能力才考虑进入 `common`。

架构测试当前只锁定已经稳定的边界。遇到历史代码违反目标分层时，优先小步引入内部模型或 port，不要仅为包名整齐而一次性重写业务语义。

## 配置变更

一个能力使用一个主开关，调度和常规子流程默认跟随它，不为同一授权叠加多个必填开关。真实下单、对外发布和 AI 自动调参保留独立授权；凭据、额度、幂等和资金状态按各自职责校验。细分开关仅用于可选暂停或兼容旧配置，不能成为常规启用的额外步骤。配置类与运行模板的默认值存在差异时，必须在运维文档中说明。

新增或修改配置时同步完成：

- `src/main/resources/application.yml` 中的默认值和说明；
- 对应 `@ConfigurationProperties`；
- `.env.example` 中的环境变量名，不填写真实值；
- [运维手册](../docs/backend/OPERATIONS.md) 中的启动、回滚或观测步骤；
- 配置绑定或安全门禁测试。

## 数据库变更

- `db/schema/<module>/schema.sql` 按模块保存当前完整结构，供查阅与离线结构测试；运行时仅执行 Flyway 版本迁移，`spring.sql.init.mode=never`；
- 新增 `db/migration/V<N>__description.sql`（全库唯一递增版本），同时更新所属模块完整结构、Dockerfile 的 `com.trade.database.version` 和迁移测试；已发布的迁移及支持文件禁止修改；
- `db/upgrade/<module>/` 仅保存 Flyway 接入前的手工历史补丁，不扫描执行，不能当作版本链；跨模块历史补丁放在 `db/upgrade/legacy/`；
- SQL 必须注明适用旧结构、前置条件、验证方式和不可逆操作；
- 表结构、Mapper 接口、Row 类型和 XML 必须在同一变更中保持一致；
- 合并前至少在隔离数据库验证，生产执行前必须备份。

主键、业务唯一约束、MyBatis 回填与重建条件统一见 [数据库开发规范](src/main/resources/db/README.md)，旧库适用条件和顺序见 [升级说明](src/main/resources/db/upgrade/README.md)。主键变更同步升级脚本及测试；不得放宽 `DatabaseSchemaInitializationTest` 断言。

## 测试与质量门禁

测试目录镜像生产包路径。优先写不依赖网络、时间竞争和真实凭据的确定性测试；并发与异步流程使用可控阻塞 fake、完成指标或明确超时断言。

默认选择能验证本次改动的最小充分范围，不要求每次修改都全量回归：

| 改动范围 | 验证范围 |
| --- | --- |
| 纯文档、注释 | 内容、链接、命令与 `git diff --check`，不运行 Java 测试 |
| 单个规则、方法或缺陷修复 | 直接相关测试类，覆盖本次行为与必要边界 |
| 模块内部流程 | 对应包或模块的测试，按需补充该流程的集成测试 |
| 共享客户端、port、调度或配置 | 修改处测试，加直接受影响调用方/模块的契约、绑定及集成测试；不自动升级为全量 |
| SQL、Mapper、迁移、包结构 | 对应持久化、结构/迁移或架构测试，按实际影响选择 |
| 全局构建/运行基础变动，影响无法合理限定 | 说明原因后运行全量测试 |

真实资金、幂等、并发或鉴权的改动需要覆盖相应失败路径和集成行为，但这些标签本身不意味着所有无关模块都要重测。用户明确要求全量或执行发布/CI 验收时运行全量；同一代码状态已有可信的通过结果时可复用，不重复补跑。失败后先修复并重跑受影响用例，只有新发现的影响或回归风险才扩大范围。

后端目前是单个 Maven 工程，业务模块是 Java 包；按模块选择使用 `-Dtest`，不是 `-pl`。以下命令从 `backend/` 执行，PowerShell 中保留整个参数外侧的引号：

```powershell
# 单个或多个相关测试类
.\mvnw.cmd "-Dtest=XConfigurationTest,WeiboTelegramReviewPropertiesTest" test

# 一个业务模块：覆盖 com.trade.x 下所有现有测试类
.\mvnw.cmd "-Dtest=com/trade/x/**/*Test.java" test

# 共享 Telegram 修改：客户端及两个直接调用模块
.\mvnw.cmd "-Dtest=com/trade/client/telegram/**/*Test.java,com/trade/x/**/*Test.java,com/trade/weibo/**/*Test.java,TradingAiPropertiesTest" test

# 移动/删除/重命名类型：清掉旧 class，仍只运行受影响模块和架构测试
.\mvnw.cmd clean "-Dtest=com/trade/x/**/*Test.java,PackageArchitectureTest" test

# 明确需要全量时；普通代码修改无需 clean
.\mvnw.cmd test

# 发布构建或需要排除旧 class 干扰的全量验证
.\mvnw.cmd clean test
```

`-Dtest` 只限制测试执行范围，Maven 仍可能编译整个工程及测试源码；不为避免这一步而拆分模块或添加新的测试调度框架。包路径和逗号组合语法见 [Surefire test 参数](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html#test)。上述用例清单是示例，执行前沿实际调用链确认受影响范围，并核对报告确实运行了预期用例；不得设置 `failIfNoSpecifiedTests=false` 掩盖模式未匹配。

`clean` 只用于需要清理旧产物的情况，不是每次测试的前置步骤。已有相关测试通过后，除非又修改了代码、出现失败或发现未覆盖的影响，不重复运行，不在交付前无条件追加全量。交付说明记录实际命令、范围、结果及外部依赖未验证项即可。

测试不得真实下单、发布内容、消耗付费 AI token 或写入生产系统。若外部集成测试缺少凭据，应通过条件显式跳过并在测试报告中可见。

## 文档维护

文档职责与更新入口统一见 [文档总导航的维护规则](../docs/README.md#维护规则)。模块入口变化同步对应 `package-info.java`；开发命令变化同步本页与后端 README 的启动步骤。

## 评审清单

- [ ] 变更放在正确业务域和层级；
- [ ] API、数据库和配置兼容性已说明；
- [ ] 新增或变更的业务表使用自增 BIGINT `id` 单列主键，业务唯一约束与关联语义保留；
- [ ] 真实资金与外部副作用仍有明确门禁；
- [ ] 幂等、并发、失败隔离、审计和停机行为已覆盖；
- [ ] 测试覆盖成功、失败和边界路径；
- [ ] 文档、示例配置和代码保持一致；
- [ ] 未提交凭据、生成物、日志或本地状态；
- [ ] 与改动范围相符的验证通过，并说明命令、覆盖范围及未验证项；仅在上述适用场景要求全量。
