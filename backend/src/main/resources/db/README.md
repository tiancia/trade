# 数据库 SQL 目录

SQL 区分运行时版本迁移、按模块维护的完整结构和接入前的历史补丁：

```text
db/
├─ migration/                 # Flyway 不可变版本 SQL，目前 V1
├─ migration-support/         # Java V2 使用并校验的不可变 SQL
├─ schema/
│  ├─ ai/schema.sql
│  ├─ trading/schema.sql
│  ├─ polymarket/schema.sql
│  ├─ textgame/schema.sql
│  ├─ marketplace/schema.sql
│  ├─ weibo/schema.sql
│  └─ x/schema.sql
└─ upgrade/
   ├─ README.md
   ├─ ai/
   ├─ trading/
   ├─ polymarket/
   ├─ textgame/
   ├─ marketplace/
   ├─ weibo/
   ├─ x/
   └─ legacy/                 # 跨模块历史升级
```

## 完整结构：schema

查找当前表定义时优先查看所属模块的 `schema.sql`：

| 模块 | 完整结构 | 数据归属 |
| --- | --- | --- |
| `ai` | [schema/ai/schema.sql](schema/ai/schema.sql) | 共享 AI 响应解析失败审计 |
| `trading` | [schema/trading/schema.sql](schema/trading/schema.sql) | OKX 决策、行情、策略、回测、订单、资金状态与领导租约 |
| `polymarket` | [schema/polymarket/schema.sql](schema/polymarket/schema.sql) | Polymarket 决策、请求、执行审计与持久执行预留 |
| `textgame` | [schema/textgame/schema.sql](schema/textgame/schema.sql) | 剧情、版本、会话与事件 |
| `marketplace` | [schema/marketplace/schema.sql](schema/marketplace/schema.sql) | 集市用户、商品、会话与消息 |
| `weibo` | [schema/weibo/schema.sql](schema/weibo/schema.sql) | 微博 OAuth、账号、草稿、审核与发布记录 |
| `x` | [schema/x/schema.sql](schema/x/schema.sql) | X 草稿、内容规则、审核与发布记录 |

`application.yml` 的 `spring.sql.init.mode=never`。`schema-locations` 列出的七份文件仅供查阅及离线结构测试，运行时统一由 Flyway 执行版本迁移，避免两种初始化机制同时改库。

## 随后端发布自动升级

后端启动先完成 Flyway，再进入应用就绪及后台任务启动阶段。`flyway_schema_history` 记录已应用版本、校验值和结果；重复启动只执行新增版本。当前 V1 是冻结的接入前完整结构，Java V2 添加 Trading 连亏会计字段、共享策略选择及 SELL 检查点回填。V2 支持核验并接入已经手工应用过同一变更的库。

旧库首次接入时先核验已存在业务表的列类型、必填约束、自增主键和业务唯一键；只接纳冻结结构及已知 V2 表，不会把任意非空库直接标记为已升级。通过后建立版本 0 基线，再执行 V1 补齐缺失模块及 V2。更早的主键或字段结构须先按 [历史升级说明](upgrade/README.md) 补齐前置条件；无法确认的结构直接阻止启动。数据库用户需要本库的建表、改表、索引、读写及备份权限。

生产使用 [发布脚本](../../../../../deploy/scripts/release.sh)：先拉取镜像并检查镜像 schema 版本，停止旧后端，在 `deploy/releases/database-backups/` 保存并校验本地压缩备份，再启动新后端完成迁移，等待健康及版本检查通过后才记录发布成功。仅发布前端不操作数据库。部署前应停止本机之外所有共享该库的旧实例和其他写入者；迁移锁串行化迁移，不会停止其他主机的业务写入。

MySQL DDL 可能立即提交。迁移失败会阻止应用启动与发布完成，发布脚本停止新后端；不会自动修复 Flyway 记录、回退 SQL 或恢复备份。先核实实际结构、数据和失败记录，再制定恢复或向前修复方案。旧镜像若落后于数据库版本会被拒绝，包括不带 schema 标签的历史镜像；应用镜像回滚不等于数据库回滚。定时异机备份仍需独立配置。

后续改库须在同一变更中：

1. 追加全库唯一递增版本，例如 `db/migration/V3__add_trading_index.sql`；更新所属模块完整 schema。
2. 将 `backend/Dockerfile` 的 `com.trade.database.version` 提升为该最高版本；迁移测试会核对标签与迁移链。
3. 验证空库、旧版本升级及重复启动，保持主键、业务唯一约束、Mapper 和历史数据语义。

版本文件随镜像构建推送，正常部署无需额外上传或粘贴 SQL。已发布的 V1、Java V2、支持 SQL 及后续版本不得修改、重命名或删除；修改历史内容会触发校验失败，修正只能新增版本。不要使用 `clean`、盲目 `baseline` 或自动 `repair` 绕过失败。

### 开发库删表后重建

对允许丢弃全部数据的独立开发库，创建一个全新的空数据库后启动后端，Flyway 会建立当前 56 张业务表和迁移历史表。业务表均使用自增 `id`，无需再执行历史自增主键补丁。

此方式仅适用于允许丢弃全部数据的开发库：删表会丢失订单、资金状态、审核、账号、会话和其他历史记录，重建只恢复 schema 中的初始化种子，不恢复旧数据。先停止所有应用实例和其他写入者，并关闭自动任务、真实下单及对外发布开关；需保留数据时使用旧库升级脚本。

Flyway 不负责创建 MySQL 数据库本身。已有 Flyway 历史的库不能只删除业务表后期待重建：已应用版本不会重跑。只清空数据（如 `TRUNCATE`）不会改变表结构，也不会重建种子；不要单独删除或篡改迁移历史来绕过校验。

## 主键开发规范

全部 56 张业务表统一使用以下单列主键定义；后续新增或重建的业务表也必须遵守（Flyway 自身的历史表使用工具规定结构）：

```sql
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY
```

普通业务 INSERT 省略 `id`，由数据库生成，不得用 `MAX(id)+1` 等应用计算替代自增；需要返回新主键时，使用 MyBatis 生成键回填，数字主键对应的 Java 字段使用 `Long`。初始化种子或存量迁移确需保留固定 ID 时允许显式写入。

账号、令牌哈希、UUID、回测标识及组合业务键保留唯一约束，用于现有查询、去重、行锁和关联。
新增表的业务标识也应使用独立字段及所需的单列或复合 `UNIQUE` 约束，不得作为数据库主键。不能用自增行 ID 替代交易所用户 ID、订单幂等键或审核回调身份。
微博与 X 草稿的原 UUID `id` 改为 `post_key`，历史表同样使用 `post_key`；审核投递的原 UUID `id` 改为 `delivery_key`。
Mapper 将这些业务键继续映射到原有 Java/API 的标识字段，已有审核按钮和关联记录可以继续使用。

主键变更需同时更新模块 schema、新的版本迁移、Mapper/Row 和相邻测试；新增数据库模块还需更新完整结构列表。
[`DatabaseSchemaInitializationTest`](../../../test/java/com/trade/architecture/DatabaseSchemaInitializationTest.java) 执行配置中的 schema，校验每张表只以 `id` 为主键、类型为 BIGINT 且启用自增，并检查重启时已有初始化状态不被覆盖；不得放宽这些断言来容纳不符合规范的新表。

### 重复写入与自增编号

自增 ID 允许有缺口，不能用最大 ID 推算行数。常态更新应避免反复申请新编号：K 线按业务键批量区分更新与插入；OKX / Polymarket 的 AI 请求、响应和执行审计，以及微博账号令牌，先查业务键，已存在时执行 `UPDATE`，首次插入仍保留唯一键冲突兜底。判断存在性不依赖更新影响行数，兼容内容未变化及不同 `useAffectedRows` 设置。

领导租约、持仓 / 风控 / 资金停止、订单幂等、累计成交检查点、模拟账户头、AI / Polymarket 守卫、X / 微博账号门与轮询行保留原来的原子创建 SQL。适配器仅在事务成功提交后记住“该业务键已初始化”，后续加锁确认行仍存在，再跳过重复创建；行丢失时立即失效旧标记并重新初始化。事务回滚或创建失败不会留下标记。该进程内记忆只保存存在性，设有 1024 条清理阈值，不缓存资金值、订单状态或锁的所有权；每轮所需的行锁、CAS 与条件更新仍在数据库执行，加锁查询关闭结果缓存并清理本地会话缓存。

进程重启、存在性记忆清理、并发首次创建或事务回滚仍可能消耗额外编号，不承诺 ID 连续。手工重建 / 恢复数据库时仍须停止所有应用实例，完成后重新启动，避免沿用旧数据库的初始化记忆；无需重排既有 ID。

## 旧库升级：upgrade

`upgrade/<module>/` 保存所属模块的历史升级补丁；`upgrade/legacy/` 保存跨模块历史变更。文件保留原名，便于对应已有升级记录。

启动不会执行这些历史补丁。部分脚本面向不同历史结构，包含 `ALTER TABLE`、数据转换或重叠建表操作，不能按文件名全部串行执行。只有首次接入前的旧结构缺少前置条件时才评估适用补丁；升级前备份并对比实际结构，依赖与使用步骤见 [升级说明](upgrade/README.md)。

已按旧结构建库时，升级为自增主键前，需按实际存在的模块执行
`upgrade/{trading,textgame,marketplace,weibo,x}/migration_add_auto_increment_primary_keys.sql`。
这五份脚本只适用于旧主键结构，不能在新库或已升级的表上重复执行。
