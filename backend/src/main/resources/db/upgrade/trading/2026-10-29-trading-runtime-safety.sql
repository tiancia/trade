-- Applies to the trading schema through 2026-10-28, without these risk columns
-- or the shared selection table. Stop ALL old writers and back up first.
-- Check existing schemas before running: ALTER TABLE is not repeatable and
-- MySQL DDL auto-commits. Roll back with a verified backup and matching binary.
-- Verify SHOW CREATE TABLE for both tables, the business UNIQUE key, and
-- SELECT account_scope, closed_trade_accounting_version FROM okx_risk_state.
-- No financial balances, order IDs or fill checkpoints are deleted.
-- Before execution, verify each positive SELL checkpoint has its order row,
-- positive average_fill_price and a zero fee or a base/quote fee currency.
-- Resolve unsupported historical fee currencies before migrating these totals.

CREATE TABLE IF NOT EXISTS `okx_strategy_selection` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `account_scope` varchar(32) NOT NULL,
    `inst_id` varchar(64) NOT NULL,
    `strategy_id` varchar(128),
    `revision` bigint NOT NULL DEFAULT 0,
    `changed_at` datetime(6),
    UNIQUE KEY `uk_okx_strategy_selection_scope_inst` (`account_scope`, `inst_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `okx_risk_state`
    ADD COLUMN `open_trade_realized_pnl` decimal(38,18) NULL;
ALTER TABLE `okx_risk_state`
    ADD COLUMN `closed_trade_accounting_version` int NOT NULL DEFAULT 0;

-- SELL checkpoints formerly stored zero quote cost. Seed their already-applied
-- net proceeds before deploying the new delta-PnL accounting. Only spot market
-- orders exist in this ledger; quote fees are signed negative by OKX.
UPDATE okx_order_fill_ledger
SET applied_quote_cost = cumulative_filled_size * average_fill_price
        + CASE WHEN fee_ccy = (SELECT SUBSTRING(o.inst_id, LOCATE('-', o.inst_id) + 1)
                FROM okx_orders o WHERE o.id = okx_order_fill_ledger.order_id)
            THEN COALESCE(fee, 0) ELSE 0 END
WHERE side = 'sell' AND cumulative_filled_size > 0;

-- Before startup, explicitly seed each PAPER/LIVE selection from the verified
-- old leader's JSON (account_scope, inst_id, strategy_id, revision, changed_at).
-- If left empty, the first process imports its own legacy file once. Do not
-- start conflicting legacy files together. No old binary may run concurrently.
-- Old equity-sample loss streaks/cooldowns reset lazily at the first new risk
-- transition. Pre-upgrade open positions do not count toward closed-trade
-- streaks; their next flat entry starts reliable round-trip accounting.
