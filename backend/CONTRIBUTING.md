# 贡献指南

本页负责后端开发流程与质量门禁。模块归属见 [模块目录](../docs/backend/MODULES.md)，结构规范见 [架构说明](../docs/backend/ARCHITECTURE.md)，Maven 命令从 `backend/` 执行。

## 开发流程

1. 从最新代码创建短生命周期分支，保持一次变更只解决一个主题。
2. 用 `git status --short` 识别并保护已有工作区改动。
3. 在 [模块目录](../docs/backend/MODULES.md) 中确认归属、入口和依赖边界。
4. 先补测试或明确验收条件，再实现最小完整改动。
5. 更新与行为变化直接相关的配置、数据库、接口和运维文档。
6. 运行定向测试和完整 `clean test`，再提交评审。

## 目录与依赖

新功能进入所属业务域，目录、类型放置和依赖方向以 [架构说明](../docs/backend/ARCHITECTURE.md) 为准。只有至少两个业务域已经稳定复用的纯能力才考虑进入 `common`。

架构测试当前只锁定已经稳定的边界。遇到历史代码违反目标分层时，优先小步引入内部模型或 port，不要仅为包名整齐而一次性重写业务语义。

## 配置变更

配置默认值应适合本地安全启动。真实下单、自动运行、付费 AI 调用和对外发布必须默认关闭，并且不能只依赖一个容易误开的开关。

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

```powershell
# 单个测试类
.\mvnw.cmd -q "-Dtest=PackageArchitectureTest" test

# 完整交付门禁
.\mvnw.cmd clean test
```

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
- [ ] `clean test` 通过，或明确记录未执行原因。
