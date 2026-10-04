# 数据库 SQL 目录

SQL 按所属模块整理，同时区分完整结构与旧库升级补丁：

```text
db/
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
| `polymarket` | [schema/polymarket/schema.sql](schema/polymarket/schema.sql) | Polymarket 决策、请求与执行审计 |
| `textgame` | [schema/textgame/schema.sql](schema/textgame/schema.sql) | 剧情、版本、会话与事件 |
| `marketplace` | [schema/marketplace/schema.sql](schema/marketplace/schema.sql) | 集市用户、商品、会话与消息 |
| `weibo` | [schema/weibo/schema.sql](schema/weibo/schema.sql) | 微博 OAuth、账号、草稿、审核与发布记录 |
| `x` | [schema/x/schema.sql](schema/x/schema.sql) | X 草稿、内容规则、审核与发布记录 |

`application.yml` 的 `spring.sql.init.mode=always`，`schema-locations` 显式列出这七个文件的执行顺序。应用启动只执行该列表，不扫描并执行整个 `db/` 目录。新增数据库模块时需要同步更新启动列表。

schema 使用 `CREATE TABLE IF NOT EXISTS`，既可初始化新库，也会为已有库创建缺失的表；已有表的字段、索引和历史数据不会因此升级。目录拆分不改变表名或表结构。

### 开发库删表后重建

如果原有业务表全部删除，再启动应用且上述 SQL 初始化成功，七份完整 schema 会重新创建当前的 46 张表，所有表的主键均为自增 `id`。新库或按完整 schema 重建的库无需再执行本次主键升级补丁。

此方式仅适用于允许丢弃全部数据的开发库：删表会丢失订单、资金状态、审核、账号、会话和其他历史记录，重建只恢复 schema 中的初始化种子，不恢复旧数据。先停止所有应用实例和其他写入者，并关闭自动任务、真实下单及对外发布开关；需保留数据时使用旧库升级脚本。

重建时应保留已存在的数据库，应用连接需具备建表权限，且 `spring.sql.init.mode=always` 与七个 `schema-locations` 未被运行环境覆盖。schema 不负责创建 MySQL 数据库本身。只清空数据（如 `TRUNCATE`）不会改变旧表主键，只删除部分表也不会升级留下的旧表；存在外键时还需按依赖关系处理删表。

## 主键开发规范

全部 46 张表统一使用以下单列主键定义；后续新增或重建的业务表也必须遵守：

```sql
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY
```

普通业务 INSERT 省略 `id`，由数据库生成，不得用 `MAX(id)+1` 等应用计算替代自增；需要返回新主键时，使用 MyBatis 生成键回填，数字主键对应的 Java 字段使用 `Long`。初始化种子或存量迁移确需保留固定 ID 时允许显式写入。

账号、令牌哈希、UUID、回测标识及组合业务键保留唯一约束，用于现有查询、去重、行锁和关联。
新增表的业务标识也应使用独立字段及所需的单列或复合 `UNIQUE` 约束，不得作为数据库主键。不能用自增行 ID 替代交易所用户 ID、订单幂等键或审核回调身份。
微博与 X 草稿的原 UUID `id` 改为 `post_key`，历史表同样使用 `post_key`；审核投递的原 UUID `id` 改为 `delivery_key`。
Mapper 将这些业务键继续映射到原有 Java/API 的标识字段，已有审核按钮和关联记录可以继续使用。

主键变更需同时更新模块 schema、适用的手工升级脚本、Mapper/Row 和相邻测试；新增数据库模块还需更新启动列表。
[`DatabaseSchemaInitializationTest`](../../../test/java/com/trade/architecture/DatabaseSchemaInitializationTest.java) 执行配置中的 schema，校验每张表只以 `id` 为主键、类型为 BIGINT 且启用自增，并检查重启时已有初始化状态不被覆盖；不得放宽这些断言来容纳不符合规范的新表。

## 旧库升级：upgrade

`upgrade/<module>/` 保存所属模块的历史升级补丁；`upgrade/legacy/` 保存跨模块历史变更。文件保留原名，便于对应已有升级记录。

项目没有启用 Flyway 或 Liquibase，启动不会执行这些补丁。部分脚本面向不同历史结构，包含 `ALTER TABLE`、数据转换或重叠建表操作，不能按文件名全部串行执行。升级前备份并对比实际结构，依赖与使用步骤见 [升级说明](upgrade/README.md)。

已按旧结构建库时，部署本次自增主键版本前，需按实际存在的模块执行
`upgrade/{trading,textgame,marketplace,weibo,x}/migration_add_auto_increment_primary_keys.sql`。
这五份脚本只适用于旧主键结构，不能在新库或已升级的表上重复执行。
