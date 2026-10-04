# 贡献指南

本项目按“业务域优先、域内分层”的方式维护。提交代码前请先阅读 [文档导航](docs/README.md) 和 [架构说明](docs/ARCHITECTURE.md)。

## 开发流程

1. 从最新代码创建短生命周期分支，保持一次变更只解决一个主题。
2. 用 `git status --short` 识别并保护已有工作区改动。
3. 在 [模块目录](docs/MODULES.md) 中确认归属、入口和依赖边界。
4. 先补测试或明确验收条件，再实现最小完整改动。
5. 更新与行为变化直接相关的配置、数据库、接口和运维文档。
6. 运行定向测试和完整 `clean test`，再提交评审。

## 目录与依赖

新功能优先进入所属业务域，不按技术类型创建全局目录。标准结构如下，空层不需要为了形式提前创建：

```text
<domain>/
├─ package-info.java
├─ interfaces/
│  └─ web/、scheduler/
├─ application/
│  └─ service/ 或能力包、port/
├─ domain/
│  └─ model/、exception/ 或能力包
└─ infrastructure/
   └─ persistence/、broker/、market/、event/、config/ 等
```

必须遵守以下边界：

- Controller 只处理协议和鉴权，业务流程下沉到 application service；
- domain 不依赖 interfaces、application、infrastructure；不依赖供应商 client，原始 DTO 在应用/基础设施边界转换为领域输入；
- infrastructure 实现 application port，port 不得反向依赖具体实现；
- 业务域之间不直接调用；共享代码不能反向依赖业务域；
- scheduler 只负责触发，不能复制用例逻辑；
- 只有至少两个业务域已经稳定复用的纯能力才考虑进入 `common`。

架构测试当前只锁定已经稳定的边界。遇到历史代码违反目标分层时，优先小步引入内部模型或 port，不要仅为包名整齐而一次性重写业务语义。

## 配置变更

配置默认值应适合本地安全启动。真实下单、自动运行、付费 AI 调用和对外发布必须默认关闭，并且不能只依赖一个容易误开的开关。

新增或修改配置时同步完成：

- `src/main/resources/application.yml` 中的默认值和说明；
- 对应 `@ConfigurationProperties`；
- `.env.example` 中的环境变量名，不填写真实值；
- [运维手册](docs/OPERATIONS.md) 中的启动、回滚或观测步骤；
- 配置绑定或安全门禁测试。

## 数据库变更

- `db/schema/<module>/schema.sql` 按模块保存完整结构，应用启动时按 `spring.sql.init.schema-locations` 中的显式顺序执行；
- `db/upgrade/<module>/` 是已有数据库的手工升级脚本，不会自动执行；跨模块历史补丁放在 `db/upgrade/legacy/`；
- SQL 必须注明适用旧结构、前置条件、验证方式和不可逆操作；
- 表结构、Mapper 接口、Row 类型和 XML 必须在同一变更中保持一致；
- 合并前至少在隔离数据库验证，生产执行前必须备份。

### 主键约定

- 所有业务表必须以单列 `id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY` 作为数据库主键，不使用 UUID、账号、会话或复合业务字段作为主键。
- 业务标识使用独立字段并建立所需的单列或复合 `UNIQUE` 约束；保留已有业务关联、幂等、去重、UPSERT 和行锁语义。数据库主键不能替代交易所用户 ID、审核回调身份等外部标识。
- 普通 INSERT 省略 `id`，不得用 `MAX(id)+1` 等应用计算替代数据库自增；需要返回生成值时通过 MyBatis 回填到数字主键对应的 Java `Long` 字段。初始化种子或旧数据迁移确需保留固定 ID 时允许显式写入。
- 新表及主键变更必须同步完整 schema、适用的旧库升级脚本、Mapper/Row 和测试；`DatabaseSchemaInitializationTest` 校验全部启动表的单列主键、BIGINT 类型和自增属性，不得放宽断言来规避规范。
- 空开发库可由启动 schema 重建；重启不会修改已有表结构。需要保留数据的数据库必须使用经过验证的升级脚本，不能以删表作为升级流程。

模块索引和重建条件见 [数据库目录](src/main/resources/db/README.md)，升级依赖和使用规则见 [数据库升级说明](src/main/resources/db/upgrade/README.md)。

## 测试与质量门禁

测试目录镜像生产包路径。优先写不依赖网络、时间竞争和真实凭据的确定性测试；并发与异步流程使用可控阻塞 fake、完成指标或明确超时断言。

```powershell
# 单个测试类
.\mvnw.cmd -q "-Dtest=PackageArchitectureTest" test

# 完整交付门禁
.\mvnw.cmd clean test
```

测试不得真实下单、发布内容、消耗付费 AI token 或写入生产系统。若外部集成测试缺少凭据，应通过条件显式跳过并在测试报告中可见。

## 文档更新矩阵

| 变更 | 同步更新 |
| --- | --- |
| 新模块、入口或依赖方向 | `docs/MODULES.md`、`docs/ARCHITECTURE.md`、对应 `package-info.java` |
| 启动、停机、监控或故障处理 | `docs/OPERATIONS.md` |
| 架构级取舍 | `docs/adr/` 新增 ADR，不覆盖历史决策 |
| 新环境变量 | `.env.example` 和相关运维说明 |
| 数据库升级 | 模块 schema、upgrade 脚本和升级 README；新增模块同步启动列表 |
| 开发/测试命令变化 | 本文件、根 README、`AGENTS.md` |

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
