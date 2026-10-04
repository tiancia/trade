-- Trading: use a generated numeric id as the primary key of every table.
-- Run once on the original trading schema after all earlier trading upgrades.
-- The eight tables below must still have their original business primary keys
-- and must not already have an id column or the unique indexes added here.
-- Stop every application instance and other writer, take a restorable backup,
-- and confirm the table/FK definitions before running on the intended database.
-- MySQL DDL commits implicitly: this script cannot be rolled back as one
-- transaction. On failure, keep writers stopped, inspect the completed DDL,
-- and restore the backup or resume from the failed statement after review;
-- do not blindly rerun the whole script. Adding AUTO_INCREMENT rebuilds tables
-- and assigns fresh ids; existing business keys, data and references are kept.

-- Keep the existing unique business identities before replacing their PKs.
ALTER TABLE `okx_position_state`
    ADD UNIQUE KEY `uk_okx_position_state_account_inst` (`account_scope`, `inst_id`);
ALTER TABLE `okx_risk_state`
    ADD UNIQUE KEY `uk_okx_risk_state_account_scope` (`account_scope`);
ALTER TABLE `okx_fund_safety_state`
    ADD UNIQUE KEY `uk_okx_fund_safety_state_account_scope` (`account_scope`);
ALTER TABLE `okx_trading_leader_lease`
    ADD UNIQUE KEY `uk_okx_trading_leader_lease_name` (`lease_name`);
ALTER TABLE `okx_order_fill_ledger`
    ADD UNIQUE KEY `uk_okx_order_fill_ledger_order_id` (`order_id`);
ALTER TABLE `okx_backtest_runs`
    ADD UNIQUE KEY `uk_okx_backtest_runs_run_id` (`run_id`);
ALTER TABLE `okx_backtest_metrics`
    ADD UNIQUE KEY `uk_okx_backtest_metrics_run_id` (`run_id`);
ALTER TABLE `okx_candle_cache`
    ADD UNIQUE KEY `uk_okx_candle_cache_inst_bar_ts` (`inst_id`, `bar`, `ts`);

ALTER TABLE `okx_position_state`
    DROP PRIMARY KEY,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (`id`);
ALTER TABLE `okx_risk_state`
    DROP PRIMARY KEY,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (`id`);
ALTER TABLE `okx_fund_safety_state`
    DROP PRIMARY KEY,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (`id`);
ALTER TABLE `okx_trading_leader_lease`
    DROP PRIMARY KEY,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (`id`);

-- Recreate only the affected FKs with their original delete semantics.
-- Do not disable foreign_key_checks. No writers may run during this interval.
ALTER TABLE `okx_order_fill_ledger`
    DROP FOREIGN KEY `fk_okx_order_fill_ledger_order`;
ALTER TABLE `okx_order_fill_ledger`
    DROP PRIMARY KEY,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (`id`);
ALTER TABLE `okx_order_fill_ledger`
    ADD CONSTRAINT `fk_okx_order_fill_ledger_order`
        FOREIGN KEY (`order_id`) REFERENCES `okx_orders` (`id`) ON DELETE RESTRICT;

-- Remove the dependencies on the old run_id PRIMARY index before changing it.
-- Afterward both FKs continue to reference the same unique run_id values.
ALTER TABLE `okx_backtest_trades`
    DROP FOREIGN KEY `fk_okx_backtest_trades_run`;
ALTER TABLE `okx_backtest_metrics`
    DROP FOREIGN KEY `fk_okx_backtest_metrics_run`;
ALTER TABLE `okx_backtest_runs`
    DROP PRIMARY KEY,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (`id`);
ALTER TABLE `okx_backtest_metrics`
    DROP PRIMARY KEY,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (`id`);
ALTER TABLE `okx_backtest_trades`
    ADD CONSTRAINT `fk_okx_backtest_trades_run`
        FOREIGN KEY (`run_id`) REFERENCES `okx_backtest_runs` (`run_id`) ON DELETE CASCADE;
ALTER TABLE `okx_backtest_metrics`
    ADD CONSTRAINT `fk_okx_backtest_metrics_run`
        FOREIGN KEY (`run_id`) REFERENCES `okx_backtest_runs` (`run_id`) ON DELETE CASCADE;

ALTER TABLE `okx_candle_cache`
    DROP PRIMARY KEY,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (`id`);

-- Verify all eight generated id PKs, unique business indexes, row counts and
-- the three restored FKs before restarting writers. Account-scoped fund gates,
-- lease ownership, order checkpoints and candle UPSERT keys remain unchanged.
