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
├─ web/
├─ application/
│  └─ port/
├─ model/ 或 domain/
├─ decision/、strategy/、risk/
├─ persistence/
├─ scheduler/
└─ config/
```

必须遵守以下边界：

- Controller 只处理协议和鉴权，业务流程下沉到 application service；
- model/domain 不依赖 Web、持久化类型或供应商 client；
- persistence 可以实现 application port，但不能依赖 web；
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

- `db/ai_trade_mysql_schema.sql` 是新数据库的完整基线；
- `db/migration/` 是已有数据库的手工升级脚本，不会自动执行；
- SQL 必须注明适用旧结构、前置条件、验证方式和不可逆操作；
- 表结构、Mapper 接口、Row 类型和 XML 必须在同一变更中保持一致；
- 合并前至少在隔离数据库验证，生产执行前必须备份。

完整规则见 [数据库迁移说明](src/main/resources/db/migration/README.md)。

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
| 数据库升级 | 基线、迁移脚本和迁移 README |
| 开发/测试命令变化 | 本文件、根 README、`AGENTS.md` |

## 评审清单

- [ ] 变更放在正确业务域和层级；
- [ ] API、数据库和配置兼容性已说明；
- [ ] 真实资金与外部副作用仍有明确门禁；
- [ ] 幂等、并发、失败隔离、审计和停机行为已覆盖；
- [ ] 测试覆盖成功、失败和边界路径；
- [ ] 文档、示例配置和代码保持一致；
- [ ] 未提交凭据、生成物、日志或本地状态；
- [ ] `clean test` 通过，或明确记录未执行原因。

