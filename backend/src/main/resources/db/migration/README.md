# 数据库迁移说明

本目录保存已有数据库的手工升级脚本。项目没有启用 Flyway 或 Liquibase，Spring Boot 启动时只执行 `db/ai_trade_mysql_schema.sql`，不会自动执行这里的文件。

## 使用方式

1. 备份目标数据库；
2. 对比目标库结构与 `db/ai_trade_mysql_schema.sql`；
3. 只选择目标库缺失的迁移脚本，并先在测试库执行；
4. 检查表、索引、字段类型和历史数据后，再应用到正式库；
5. 完成后重新对比完整基线，确认结构一致。

这些文件名描述变更内容，不代表可按字母顺序无条件执行。部分脚本面向不同历史版本，重复执行 `ALTER TABLE` 可能失败。

已知依赖与重叠：

- `migration_add_ai_decision_confidence.sql` 必须先于 `migration_add_okx_strategy_decision_fields.sql`；后者使用 `AFTER confidence` 和 `AFTER parsed_confidence`。
- `migration_add_okx_strategy_backtest_tables.sql` 与 `migration_add_okx_market_data_tables.sql` 都包含 `okx_candle_cache` 的创建语句。应先检查目标库并选择适用脚本，不要把两者当作可盲目串行执行的版本链。

## 脚本分组

| 脚本 | 作用 |
| --- | --- |
| `migration_add_okx_market_data_tables.sql` | OKX 行情快照和 K 线缓存 |
| `migration_add_okx_strategy_backtest_tables.sql` | 策略运行与回测表 |
| `migration_add_okx_strategy_decision_fields.sql` | OKX 决策的策略字段 |
| `migration_add_polymarket_decision_audits.sql` | Polymarket 决策、AI 请求与执行审计 |
| `migration_add_ai_response_parse_errors.sql` | 跨业务 AI 解析失败审计 |
| `migration_add_ai_decision_confidence.sql` | AI 决策置信度字段 |
| `migration_add_text_game_story_engine.sql` | 文字游戏剧情、版本、会话和事件 |
| `migration_add_weibo_oauth.sql` | 微博 OAuth state 与账号 token |
| `migration_add_marketplace.sql` | 集市用户、商品、会话和消息 |
| `migration_normalize_bigint_decision_schema.sql` | 历史决策主键/外键类型归一化 |

现有文件是不同历史阶段留下的补丁，并非每个文件都带完整头注释。新增或修改迁移时，应在脚本头部补充适用的旧结构、前置条件、验证 SQL 和不可逆操作，同时更新本表。
