# 数据库升级说明

本目录按模块保存已有数据库的手工升级脚本，跨模块历史脚本归 `legacy/`。项目没有启用 Flyway 或 Liquibase，Spring Boot 启动时只执行 `spring.sql.init.schema-locations` 中显式配置的模块 schema，不会执行这里的文件。完整结构与模块索引见 [数据库目录](../README.md)。

## 使用方式

1. 备份目标数据库；
2. 对比目标库结构与所属模块的 `db/schema/<module>/schema.sql`；
3. 只选择目标库缺失且适用的升级脚本，并先在测试库执行；
4. 检查表、索引、字段类型和历史数据后，再应用到正式库；
5. 完成后重新对比模块 schema，确认结构一致。

这些文件名描述变更内容，不代表可按字母顺序无条件执行。部分脚本面向不同历史版本，重复执行 `ALTER TABLE` 可能失败。启动 schema 会创建缺失的表，但不会补齐已有表字段或修改索引；新表已由启动创建且结构一致时，无需再执行对应建表补丁。

已知依赖与重叠：

- `trading/migration_add_ai_decision_confidence.sql` 必须先于 `trading/migration_add_okx_strategy_decision_fields.sql`；后者使用 `AFTER confidence` 和 `AFTER parsed_confidence`。
- `trading/migration_add_okx_strategy_backtest_tables.sql` 与 `trading/migration_add_okx_market_data_tables.sql` 都包含 `okx_candle_cache` 的创建语句。应先检查目标库并选择适用脚本，不要把两者当作可盲目串行执行的版本链。
- `weibo/migration_add_weibo_review_delivery.sql` 依赖 `weibo_post`，先有 OAuth 与工作流表；手工建表时依次使用 `weibo/migration_add_weibo_oauth.sql` 和 `weibo/migration_add_weibo_workflow.sql`。新增审核投递与 bot 轮询表，不保存 bot token；审核决定、租约和截止时间使用微秒精度。
- `x/migration_add_x_workflow.sql` 独立创建 X 的六张工作流表，包含内容规则快照、版本历史、发布尝试、审核投递和单 bot 轮询租约。无需先执行微博升级，不保存 X 或 Telegram 凭据。
- `legacy/migration_normalize_bigint_decision_schema.sql` 涉及历史 Trading 与 Polymarket 决策主键、外键及数据转换。它不是新增模块的初始化脚本，执行前须确认适用旧结构。

## 脚本分组

### 自增主键升级

所有模块的完整 schema 使用 `id BIGINT AUTO_INCREMENT PRIMARY KEY`。AI 和 Polymarket 的表已符合这一约定，无需升级。
以下五个脚本将旧结构中剩余 24 张表的主键切换为自增 `id`，保留旧标识及其唯一约束，不删除或重写业务数据：

| 模块 | 升级脚本 | 受影响表数 |
| --- | --- | --- |
| Trading | [trading/migration_add_auto_increment_primary_keys.sql](trading/migration_add_auto_increment_primary_keys.sql) | 8 |
| Textgame | [textgame/migration_add_auto_increment_primary_keys.sql](textgame/migration_add_auto_increment_primary_keys.sql) | 1 |
| Marketplace | [marketplace/migration_add_auto_increment_primary_keys.sql](marketplace/migration_add_auto_increment_primary_keys.sql) | 1 |
| Weibo | [weibo/migration_add_auto_increment_primary_keys.sql](weibo/migration_add_auto_increment_primary_keys.sql) | 8 |
| X | [x/migration_add_auto_increment_primary_keys.sql](x/migration_add_auto_increment_primary_keys.sql) | 6 |

适用旧结构：目标表仍以账号、业务 UUID、令牌哈希或复合业务字段为主键，且微博/X 的草稿与审核投递 `id` 仍为 UUID。
如从历史建表脚本初始化旧模块，先完成原建表及审核投递升级，再运行对应的自增主键补丁；直接使用当前完整 schema 建立的新库无需执行。
各模块可独立升级，但必须停掉所有应用实例和其他写入者，备份数据库，并在测试库先验证。
升级后再部署与新列名匹配的 Mapper；旧版微博/X Mapper 无法写入升级后的 `id`，不能与新版混跑。

微博/X 的 UUID 草稿字段从 `id` 更名为 `post_key`，审核投递更名为 `delivery_key`，外键仍关联原 UUID。
HTTP 标识和审核回调保持现有格式。旧账号、轮询机器人、订单、回测、行情和会话键仍为 UNIQUE，保留 UPSERT、去重与行锁语义。
新数字主键只标识数据库行，不能用于替代交易所用户 ID、订单业务键或审核身份。

升级验证：对比升级前后各表行数、业务标识、唯一索引、外键和关联记录；确认每表仅以 `id` 为主键，类型为 `bigint` 且 `EXTRA` 含 `auto_increment`：

```sql
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_KEY, EXTRA
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_KEY = 'PRI'
ORDER BY TABLE_NAME;
```

在测试库验证不指定 `id` 的插入、重复业务键拒绝或 UPSERT、乐观锁与历史记录后，再启用任务。
MySQL DDL 会隐式提交，无法用事务回滚；脚本不能重复执行。部分执行失败时先检查实际结构再恢复，回滚使用已验证的备份并匹配旧版应用，不能直接删除业务唯一键。

### 历史模块补丁

| 模块 / 脚本 | 作用 |
| --- | --- |
| [trading/migration_add_okx_order_idempotency_state_machine.sql](trading/migration_add_okx_order_idempotency_state_machine.sql) | OKX 订单幂等、乐观锁状态机与转换历史 |
| [trading/migration_add_okx_financial_safety_state.sql](trading/migration_add_okx_financial_safety_state.sql) | MySQL 权威仓位/成本/风险、累计成交账本与持久资金停止 |
| [trading/migration_add_okx_trading_leader_lease.sql](trading/migration_add_okx_trading_leader_lease.sql) | 多实例交易领导租约与所有权 fencing token |
| [trading/migration_add_okx_market_data_tables.sql](trading/migration_add_okx_market_data_tables.sql) | OKX 行情快照和 K 线缓存 |
| [trading/migration_add_okx_strategy_backtest_tables.sql](trading/migration_add_okx_strategy_backtest_tables.sql) | 策略运行与回测表 |
| [trading/migration_add_okx_strategy_decision_fields.sql](trading/migration_add_okx_strategy_decision_fields.sql) | OKX 决策的策略字段 |
| [trading/migration_add_ai_decision_confidence.sql](trading/migration_add_ai_decision_confidence.sql) | Trading AI 决策置信度字段 |
| [polymarket/migration_add_polymarket_decision_audits.sql](polymarket/migration_add_polymarket_decision_audits.sql) | Polymarket 决策、AI 请求与执行审计 |
| [ai/migration_add_ai_response_parse_errors.sql](ai/migration_add_ai_response_parse_errors.sql) | 跨业务 AI 解析失败审计 |
| [textgame/migration_add_text_game_story_engine.sql](textgame/migration_add_text_game_story_engine.sql) | 文字游戏剧情、版本、会话和事件 |
| [weibo/migration_add_weibo_oauth.sql](weibo/migration_add_weibo_oauth.sql) | 微博 OAuth state 与账号 token |
| [weibo/migration_add_weibo_workflow.sql](weibo/migration_add_weibo_workflow.sql) | 微博事件草稿、审核版本历史、发布尝试及账号配额锁；先有 OAuth 表 |
| [weibo/migration_add_weibo_review_delivery.sql](weibo/migration_add_weibo_review_delivery.sql) | Telegram 审核投递身份、首个回调决定与单 bot 持久轮询 cursor/数据库租约；先有 workflow 表 |
| [x/migration_add_x_workflow.sql](x/migration_add_x_workflow.sql) | 独立 X 草稿、内容规则快照、审核版本历史、发布尝试、账号配额锁与 Telegram 审核投递/轮询租约 |
| [marketplace/migration_add_marketplace.sql](marketplace/migration_add_marketplace.sql) | 集市用户、商品、会话和消息 |
| [legacy/migration_normalize_bigint_decision_schema.sql](legacy/migration_normalize_bigint_decision_schema.sql) | 跨模块历史决策主键/外键类型归一化 |

现有文件是不同历史阶段留下的补丁，并非每个文件都带完整头注释。新增或修改升级脚本时，应在脚本头部补充适用的旧结构、前置条件、验证 SQL 和不可逆操作，同时更新本表。
