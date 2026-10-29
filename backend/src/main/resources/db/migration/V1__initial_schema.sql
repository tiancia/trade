-- Frozen V1 baseline before the 2026-10-29 trading runtime change.
-- Never edit an applied version. Add the next Vn migration for every schema or data change.
-- Module: ai
-- Complete schema for the ai module.
-- Loaded at application startup; existing database upgrades are manual under db/upgrade/ai/.
-- CREATE TABLE IF NOT EXISTS does not add missing columns to existing tables.

-- -----------------------------------------------------------------------------
-- Shared AI response parse-error audit
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_response_parse_errors` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `source` varchar(64) NOT NULL,
    `phase` varchar(64),
    `related_id` varchar(128),
    `prompt_text` mediumtext,
    `raw_response` mediumtext,
    `error_message` text NOT NULL,
    `fallback_action` varchar(64),
    `metadata_json` json,
    KEY `idx_ai_response_parse_errors_created_at` (`created_at`),
    KEY `idx_ai_response_parse_errors_source_phase` (`source`, `phase`, `created_at`),
    KEY `idx_ai_response_parse_errors_related_id` (`related_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Module: trading
-- Complete schema for the trading module.
-- Loaded at application startup; existing database upgrades are manual under db/upgrade/trading/.
-- CREATE TABLE IF NOT EXISTS does not add missing columns to existing tables.

-- -----------------------------------------------------------------------------
-- OKX trading, strategy, backtest, and market-data storage
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `okx_decision_runs` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `started_at` datetime(6) NOT NULL,
    `completed_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `inst_id` varchar(64) NOT NULL,
    `inst_type` varchar(32),
    `base_ccy` varchar(32),
    `quote_ccy` varchar(32),
    `td_mode` varchar(32),
    `trigger_type` varchar(64),
    `trigger_reason` text,
    `trigger_details_json` json,
    `action` varchar(16),
    `decision_reason` text,
    `buy_quote_amount` decimal(38,18),
    `sell_base_amount` decimal(38,18),
    `requested_order_size` decimal(38,18),
    `win_probability` decimal(38,18),
    `confidence` decimal(38,18),
    `strategy_bias` varchar(32),
    `strategy_thesis` text,
    `strategy_invalidation` text,
    `strategy_horizon` varchar(128),
    `last_price` decimal(38,18),
    `available_base` decimal(38,18),
    `available_quote` decimal(38,18),
    `execution_status` varchar(64),
    `skip_reason` text,
    `error` text,
    KEY `idx_okx_decision_runs_started_at` (`started_at`),
    KEY `idx_okx_decision_runs_inst_action` (`inst_id`, `action`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_ai_requests` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_run_id` bigint NOT NULL UNIQUE,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `prompt_text` text,
    `ai_parameters_json` json,
    CONSTRAINT `fk_okx_ai_requests_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `okx_decision_runs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_ai_responses` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_run_id` bigint NOT NULL UNIQUE,
    `received_at` datetime(6) NOT NULL,
    `raw_response` text,
    `parsed_action` varchar(16),
    `parsed_reason` text,
    `parsed_buy_quote_amount` decimal(38,18),
    `parsed_sell_base_amount` decimal(38,18),
    `parsed_order_size` decimal(38,18),
    `parsed_win_probability` decimal(38,18),
    `parsed_confidence` decimal(38,18),
    `parsed_strategy_bias` varchar(32),
    `parsed_strategy_thesis` text,
    `parsed_strategy_invalidation` text,
    `parsed_strategy_horizon` varchar(128),
    CONSTRAINT `fk_okx_ai_responses_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `okx_decision_runs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_order_executions` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_run_id` bigint NOT NULL UNIQUE,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `inst_id` varchar(64) NOT NULL,
    `side` varchar(16),
    `td_mode` varchar(32),
    `order_type` varchar(32),
    `target_currency` varchar(32),
    `order_size` decimal(38,18),
    `order_id` varchar(128),
    `client_order_id` varchar(128),
    `execution_status` varchar(64),
    `skip_reason` text,
    `filled_base_amount` decimal(38,18),
    `average_fill_price` decimal(38,18),
    `fee` decimal(38,18),
    `fee_ccy` varchar(32),
    `error` text,
    KEY `idx_okx_order_executions_order_id` (`order_id`),
    CONSTRAINT `fk_okx_order_executions_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `okx_decision_runs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Operational order ledger. Unlike okx_order_executions (decision audit), this
-- row is committed before the external request and owns idempotency/state.
CREATE TABLE IF NOT EXISTS `okx_orders` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `idempotency_key` varchar(64) NOT NULL,
    `client_order_id` varchar(32) NOT NULL,
    `exchange_order_id` varchar(128),
    `decision_id` varchar(64),
    `strategy_id` varchar(128),
    `inst_id` varchar(64) NOT NULL,
    `action` varchar(32) NOT NULL,
    `side` varchar(16) NOT NULL,
    `td_mode` varchar(32),
    `order_type` varchar(32),
    `target_currency` varchar(32),
    `requested_size` decimal(38,18) NOT NULL,
    `status` varchar(32) NOT NULL,
    `version` bigint NOT NULL DEFAULT 0,
    `filled_base_amount` decimal(38,18),
    `average_fill_price` decimal(38,18),
    `fee` decimal(38,18),
    `fee_ccy` varchar(32),
    `failure_code` varchar(64),
    `failure_message` text,
    `created_at` datetime(6) NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `submitted_at` datetime(6),
    `completed_at` datetime(6),
    UNIQUE KEY `uk_okx_orders_idempotency_key` (`idempotency_key`),
    UNIQUE KEY `uk_okx_orders_client_order_id` (`client_order_id`),
    KEY `idx_okx_orders_exchange_order_id` (`exchange_order_id`),
    KEY `idx_okx_orders_status_updated` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_order_status_history` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `order_id` bigint NOT NULL,
    `from_status` varchar(32),
    `to_status` varchar(32) NOT NULL,
    `version` bigint NOT NULL,
    `reason` varchar(255),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_okx_order_status_history_version` (`order_id`, `version`),
    KEY `idx_okx_order_status_history_created` (`created_at`),
    CONSTRAINT `fk_okx_order_status_history_order`
        FOREIGN KEY (`order_id`) REFERENCES `okx_orders` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Capital-bearing state. JSON strategy memory is intentionally not authoritative
-- for these rows.
CREATE TABLE IF NOT EXISTS `okx_position_state` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `account_scope` varchar(32) NOT NULL,
    `inst_id` varchar(64) NOT NULL,
    `position_side` varchar(16) NOT NULL DEFAULT 'net',
    `quantity` decimal(38,18) NOT NULL DEFAULT 0,
    `average_cost` decimal(38,18) NOT NULL DEFAULT 0,
    `exchange_quantity` decimal(38,18),
    `last_reconciled_at` datetime(6),
    `version` bigint NOT NULL DEFAULT 0,
    `created_at` datetime(6) NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    UNIQUE KEY `uk_okx_position_state_account_inst` (`account_scope`, `inst_id`),
    KEY `idx_okx_position_state_reconciled` (`last_reconciled_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_risk_state` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `account_scope` varchar(32) NOT NULL,
    `current_equity` decimal(38,18) NOT NULL DEFAULT 0,
    `equity_high_watermark` decimal(38,18) NOT NULL DEFAULT 0,
    `day_start_equity` decimal(38,18) NOT NULL DEFAULT 0,
    `day_start_date` varchar(16),
    `consecutive_losses` int NOT NULL DEFAULT 0,
    `loss_cooldown_until` datetime(6),
    `last_trade_time` datetime(6),
    `consecutive_open_actions` int NOT NULL DEFAULT 0,
    `last_risk_reason` text,
    `consecutive_reconciliation_failures` int NOT NULL DEFAULT 0,
    `last_reconciliation_at` datetime(6),
    `last_reconciliation_error` text,
    `version` bigint NOT NULL DEFAULT 0,
    `created_at` datetime(6) NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    UNIQUE KEY `uk_okx_risk_state_account_scope` (`account_scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_fund_safety_state` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `account_scope` varchar(32) NOT NULL,
    `status` varchar(16) NOT NULL,
    `reason` text,
    `source` varchar(64),
    `resume_reason` text,
    `last_action_error` text,
    `halted_at` datetime(6),
    `resumed_at` datetime(6),
    `updated_at` datetime(6) NOT NULL,
    `version` bigint NOT NULL DEFAULT 0,
    UNIQUE KEY `uk_okx_fund_safety_state_account_scope` (`account_scope`),
    KEY `idx_okx_fund_safety_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `okx_fund_safety_state` (
    `account_scope`, `status`, `reason`, `source`, `halted_at`, `updated_at`, `version`
)
SELECT
    'live',
    'HALTED',
    'Initial LIVE safety state requires successful reconciliation and operator resume',
    'bootstrap',
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6),
    0
WHERE NOT EXISTS (
    SELECT 1 FROM `okx_fund_safety_state` WHERE `account_scope` = 'live'
);

-- Database-backed single-writer ownership for multi-instance trading. The
-- fencing token advances on every ownership transfer.
CREATE TABLE IF NOT EXISTS `okx_trading_leader_lease` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `lease_name` varchar(128) NOT NULL,
    `owner_id` varchar(128) NOT NULL,
    `lease_until` datetime(6) NOT NULL,
    `fencing_token` bigint NOT NULL DEFAULT 1,
    `created_at` datetime(6) NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    UNIQUE KEY `uk_okx_trading_leader_lease_name` (`lease_name`),
    KEY `idx_okx_trading_leader_lease_expiry` (`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One cumulative checkpoint per order makes partial-fill and crash replay
-- idempotent. The checkpoint and position projection are updated together.
CREATE TABLE IF NOT EXISTS `okx_order_fill_ledger` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `order_id` bigint NOT NULL,
    `side` varchar(16) NOT NULL,
    `cumulative_filled_size` decimal(38,18) NOT NULL DEFAULT 0,
    `applied_position_quantity` decimal(38,18) NOT NULL DEFAULT 0,
    `applied_quote_cost` decimal(38,18) NOT NULL DEFAULT 0,
    `average_fill_price` decimal(38,18),
    `fee` decimal(38,18),
    `fee_ccy` varchar(32),
    `exchange_state` varchar(32),
    `exchange_updated_at` datetime(6),
    `version` bigint NOT NULL DEFAULT 0,
    `created_at` datetime(6) NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    UNIQUE KEY `uk_okx_order_fill_ledger_order_id` (`order_id`),
    KEY `idx_okx_order_fill_ledger_exchange_updated` (`exchange_updated_at`),
    CONSTRAINT `fk_okx_order_fill_ledger_order`
        FOREIGN KEY (`order_id`) REFERENCES `okx_orders` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_strategy_runs` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `started_at` datetime(6) NOT NULL,
    `completed_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `strategy_id` varchar(128) NOT NULL,
    `strategy_type` varchar(128) NOT NULL,
    `execution_mode` varchar(32) NOT NULL,
    `inst_id` varchar(64) NOT NULL,
    `bar` varchar(32),
    `trigger_type` varchar(64),
    `trigger_reason` text,
    `action` varchar(32),
    `decision_reason` text,
    `buy_quote_amount` decimal(38,18),
    `sell_base_amount` decimal(38,18),
    `requested_order_size` decimal(38,18),
    `metadata_json` json,
    `execution_status` varchar(64),
    `skip_reason` text,
    `error` text,
    KEY `idx_okx_strategy_runs_started_at` (`started_at`),
    KEY `idx_okx_strategy_runs_strategy` (`strategy_id`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_backtest_runs` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `run_id` varchar(64) NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `started_at` datetime(6),
    `completed_at` datetime(6),
    `status` varchar(32) NOT NULL,
    `strategy_id` varchar(128) NOT NULL,
    `inst_id` varchar(64) NOT NULL,
    `bar` varchar(32) NOT NULL,
    `from_ts` datetime(6) NOT NULL,
    `to_ts` datetime(6) NOT NULL,
    `initial_cash` decimal(38,18),
    `fee_rate` decimal(38,18),
    `slippage_rate` decimal(38,18),
    `parameter_overrides_json` json,
    `error` text,
    UNIQUE KEY `uk_okx_backtest_runs_run_id` (`run_id`),
    KEY `idx_okx_backtest_runs_created_at` (`created_at`),
    KEY `idx_okx_backtest_runs_strategy` (`strategy_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_backtest_trades` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `run_id` varchar(64) NOT NULL,
    `strategy_id` varchar(128),
    `action` varchar(32) NOT NULL,
    `ts` datetime(6) NOT NULL,
    `price` decimal(38,18),
    `base_amount` decimal(38,18),
    `quote_amount` decimal(38,18),
    `fee` decimal(38,18),
    `reason` text,
    KEY `idx_okx_backtest_trades_run_ts` (`run_id`, `ts`),
    CONSTRAINT `fk_okx_backtest_trades_run`
        FOREIGN KEY (`run_id`) REFERENCES `okx_backtest_runs` (`run_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_backtest_metrics` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `run_id` varchar(64) NOT NULL,
    `total_return` decimal(38,18),
    `max_drawdown` decimal(38,18),
    `win_rate` decimal(38,18),
    `profit_factor` decimal(38,18),
    `trade_count` int,
    `final_equity` decimal(38,18),
    UNIQUE KEY `uk_okx_backtest_metrics_run_id` (`run_id`),
    CONSTRAINT `fk_okx_backtest_metrics_run`
        FOREIGN KEY (`run_id`) REFERENCES `okx_backtest_runs` (`run_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_candle_cache` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `inst_id` varchar(64) NOT NULL,
    `bar` varchar(32) NOT NULL,
    `ts` bigint NOT NULL,
    `open` decimal(38,18),
    `high` decimal(38,18),
    `low` decimal(38,18),
    `close` decimal(38,18),
    `vol` decimal(38,18),
    `vol_ccy` decimal(38,18),
    `vol_ccy_quote` decimal(38,18),
    `confirm` varchar(8),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_okx_candle_cache_inst_bar_ts` (`inst_id`, `bar`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_market_snapshots` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Surrogate primary key',
    `inst_id` varchar(64) NOT NULL COMMENT 'OKX instrument identifier',
    `source` varchar(64) NOT NULL COMMENT 'REST or WebSocket collection path',
    `market_ts` bigint DEFAULT NULL COMMENT 'Ticker exchange timestamp in epoch milliseconds',
    `last_price` decimal(38,18) DEFAULT NULL COMMENT 'Last traded price',
    `last_size` decimal(38,18) DEFAULT NULL COMMENT 'Last traded size',
    `bid_price` decimal(38,18) DEFAULT NULL COMMENT 'Best bid price',
    `bid_size` decimal(38,18) DEFAULT NULL COMMENT 'Best bid size',
    `ask_price` decimal(38,18) DEFAULT NULL COMMENT 'Best ask price',
    `ask_size` decimal(38,18) DEFAULT NULL COMMENT 'Best ask size',
    `open_24h` decimal(38,18) DEFAULT NULL COMMENT 'Rolling 24-hour opening price',
    `high_24h` decimal(38,18) DEFAULT NULL COMMENT 'Rolling 24-hour high price',
    `low_24h` decimal(38,18) DEFAULT NULL COMMENT 'Rolling 24-hour low price',
    `vol_ccy_24h` decimal(38,18) DEFAULT NULL COMMENT 'Rolling 24-hour currency volume',
    `vol_24h` decimal(38,18) DEFAULT NULL COMMENT 'Rolling 24-hour base or contract volume',
    `order_book_ts` bigint DEFAULT NULL COMMENT 'Order-book exchange timestamp in epoch milliseconds',
    `sequence_id` bigint DEFAULT NULL COMMENT 'OKX order-book sequence identifier',
    `ticker_json` json DEFAULT NULL COMMENT 'Original ticker payload',
    `order_book_json` json DEFAULT NULL COMMENT 'Original order-book payload',
    `collected_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Database collection time',
    PRIMARY KEY (`id`),
    KEY `idx_okx_market_snapshots_inst_collected` (`inst_id`, `collected_at`),
    KEY `idx_okx_market_snapshots_source_collected` (`source`, `collected_at`),
    KEY `idx_okx_market_snapshots_market_ts` (`inst_id`, `market_ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Ticker and order-book snapshots collected by the trading module';

-- Adds isolated funded PAPER spot sessions. Applies to any existing trading schema.
-- No legacy or exchange balances are migrated: their starting cash and cash flows are unknown.
-- Stop writers and back up first; this additive DDL auto-commits in MySQL.
-- Verify four new tables, unique decision_id, generated id primary keys, and empty sessions before enabling PAPER.
CREATE TABLE IF NOT EXISTS `okx_paper_account_heads` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `inst_id` varchar(64) NOT NULL,
    `session_id` varchar(64),
    UNIQUE KEY `uk_okx_paper_head_instrument` (`inst_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_paper_sessions` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `session_id` varchar(64) NOT NULL,
    `inst_id` varchar(64) NOT NULL,
    `quote_ccy` varchar(32) NOT NULL,
    `status` varchar(16) NOT NULL,
    `initial_cash` decimal(38,18) NOT NULL,
    `cash` decimal(38,18) NOT NULL,
    `position_quantity` decimal(38,18) NOT NULL,
    `position_cost` decimal(38,18) NOT NULL,
    `realized_pnl` decimal(38,18) NOT NULL,
    `total_fees_quote` decimal(38,18) NOT NULL,
    `last_price` decimal(38,18),
    `valuation_at` datetime(6),
    `version` bigint NOT NULL,
    `risk_state_json` text NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    UNIQUE KEY `uk_okx_paper_session_key` (`session_id`),
    KEY `idx_okx_paper_sessions_inst` (`inst_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_paper_fills` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `session_id` varchar(64) NOT NULL,
    `decision_id` varchar(64) NOT NULL,
    `strategy_id` varchar(128),
    `strategy_config_version` bigint NOT NULL,
    `strategy_config_snapshot_json` text NOT NULL,
    `side` varchar(8) NOT NULL,
    `requested_size` decimal(38,18) NOT NULL,
    `filled_base` decimal(38,18) NOT NULL,
    `position_delta` decimal(38,18) NOT NULL,
    `price` decimal(38,18) NOT NULL,
    `cash_delta` decimal(38,18) NOT NULL,
    `fee_quote` decimal(38,18) NOT NULL,
    `realized_pnl` decimal(38,18) NOT NULL,
    `cash_after` decimal(38,18) NOT NULL,
    `position_after` decimal(38,18) NOT NULL,
    `equity_after` decimal(38,18) NOT NULL,
    `account_version` bigint NOT NULL,
    `created_at` datetime(6) NOT NULL,
    UNIQUE KEY `uk_okx_paper_fill_decision` (`decision_id`),
    KEY `idx_okx_paper_fills_session` (`session_id`, `id`),
    CONSTRAINT `fk_okx_paper_fills_session` FOREIGN KEY (`session_id`) REFERENCES `okx_paper_sessions` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_paper_equity` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `session_id` varchar(64) NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `cash` decimal(38,18) NOT NULL,
    `position_quantity` decimal(38,18) NOT NULL,
    `last_price` decimal(38,18),
    `valuation_at` datetime(6),
    `equity` decimal(38,18) NOT NULL,
    `realized_pnl` decimal(38,18) NOT NULL,
    `unrealized_pnl` decimal(38,18) NOT NULL,
    `account_version` bigint NOT NULL,
    `strategy_id` varchar(128),
    `strategy_config_version` bigint,
    `kind` varchar(16) NOT NULL,
    KEY `idx_okx_paper_equity_session` (`session_id`, `id`),
    CONSTRAINT `fk_okx_paper_equity_session` FOREIGN KEY (`session_id`) REFERENCES `okx_paper_sessions` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Persistent effective strategy configuration and immutable version history.
-- Applies to existing trading databases; adds two independent tables only.
-- Back up and stop configuration writers before applying; MySQL DDL auto-commits.
-- Verify both tables have generated BIGINT id keys and strategy/version UNIQUE keys.
-- No historical decisions are rewritten. Keep these tables when rolling back the app.
CREATE TABLE IF NOT EXISTS okx_strategy_configuration (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    strategy_id VARCHAR(191) NOT NULL,
    strategy_type VARCHAR(100) NOT NULL,
    bar VARCHAR(32) NOT NULL,
    config_version BIGINT NOT NULL,
    params_json LONGTEXT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_strategy_configuration_id (strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS okx_strategy_configuration_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    strategy_id VARCHAR(191) NOT NULL,
    strategy_type VARCHAR(100) NOT NULL,
    bar VARCHAR(32) NOT NULL,
    config_version BIGINT NOT NULL,
    params_json LONGTEXT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_strategy_configuration_version (strategy_id, config_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Applies to existing trading installations missing the two advisor tables.
-- Stop AI tasks and back up the database before running. Re-runnable CREATE IF NOT EXISTS.
-- Verify SHOW CREATE TABLE for both tables, generated BIGINT primary keys and business UNIQUE keys.
-- Does not alter trading, strategy configuration or X workflow data.
-- Rollback: disable AI auto-start/auto-apply, retain these audit tables; no destructive rollback required.
-- Independent AI advisor audit and durable rate limits. No credentials are stored.
CREATE TABLE IF NOT EXISTS okx_ai_strategy_guard (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_key VARCHAR(191) NOT NULL,
    next_review_at TIMESTAMP(6) NOT NULL,
    notification_after TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_ai_strategy_guard_scope (scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS okx_ai_strategy_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    review_id VARCHAR(64) NOT NULL,
    scope_key VARCHAR(191) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    snapshot_json LONGTEXT NULL,
    proposal_json LONGTEXT NULL,
    outcome VARCHAR(32) NOT NULL,
    detail VARCHAR(1000) NULL,
    notification_status VARCHAR(32) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_ai_strategy_review_id (review_id),
    KEY idx_ai_strategy_review_scope (scope_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Module: polymarket
-- Complete schema for the polymarket module.
-- Loaded at application startup; existing database upgrades are manual under db/upgrade/polymarket/.
-- CREATE TABLE IF NOT EXISTS does not add missing columns to existing tables.

-- Persistent single-account barrier. Never clear this row during startup or on a timeout.
CREATE TABLE IF NOT EXISTS `polymarket_execution_guard` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `guard_name` varchar(64) NOT NULL UNIQUE,
    `pending_decision_id` bigint,
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- Polymarket AI decisions and order-execution audit
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `polymarket_decision_runs` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `started_at` datetime(6) NOT NULL,
    `completed_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `execution_enabled` tinyint(1) NOT NULL DEFAULT 0,
    `market_count` int,
    `outcome_count` int,
    `action` varchar(16),
    `decision_reason` text,
    `market_id` varchar(128),
    `market_slug` varchar(512),
    `market_question` text,
    `outcome` varchar(512),
    `token_id` varchar(128),
    `limit_price` decimal(38,18),
    `max_spend_usdc` decimal(38,18),
    `win_probability` decimal(38,18),
    `confidence` decimal(38,18),
    `estimated_probability` decimal(38,18),
    `estimated_edge` decimal(38,18),
    `execution_status` varchar(64),
    `skip_reason` text,
    `error` text,
    KEY `idx_polymarket_decision_runs_started_at` (`started_at`),
    KEY `idx_polymarket_decision_runs_action` (`action`, `started_at`),
    KEY `idx_polymarket_decision_runs_token` (`token_id`, `started_at`),
    KEY `idx_polymarket_decision_runs_market_slug` (`market_slug`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `polymarket_ai_requests` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_run_id` bigint NOT NULL UNIQUE,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `prompt_text` mediumtext,
    `ai_parameters_json` json,
    CONSTRAINT `fk_polymarket_ai_requests_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `polymarket_decision_runs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `polymarket_ai_responses` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_run_id` bigint NOT NULL UNIQUE,
    `received_at` datetime(6) NOT NULL,
    `raw_response` mediumtext,
    `parsed_action` varchar(16),
    `parsed_reason` text,
    `parsed_market_id` varchar(128),
    `parsed_market_slug` varchar(512),
    `parsed_market_question` text,
    `parsed_outcome` varchar(512),
    `parsed_token_id` varchar(128),
    `parsed_limit_price` decimal(38,18),
    `parsed_max_spend_usdc` decimal(38,18),
    `parsed_win_probability` decimal(38,18),
    `parsed_confidence` decimal(38,18),
    `parsed_estimated_probability` decimal(38,18),
    `parsed_estimated_edge` decimal(38,18),
    CONSTRAINT `fk_polymarket_ai_responses_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `polymarket_decision_runs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `polymarket_order_executions` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_run_id` bigint NOT NULL UNIQUE,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `execution_status` varchar(64),
    `skip_reason` text,
    `order_response` mediumtext,
    `error` text,
    CONSTRAINT `fk_polymarket_order_executions_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `polymarket_decision_runs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Module: textgame
-- Complete schema for the textgame module.
-- Loaded at application startup; existing database upgrades are manual under db/upgrade/textgame/.
-- CREATE TABLE IF NOT EXISTS does not add missing columns to existing tables.

-- -----------------------------------------------------------------------------
-- Text-game stories, published versions, sessions, and event history
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `text_game_stories` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `story_key` varchar(128) NOT NULL UNIQUE,
    `title` varchar(255) NOT NULL,
    `summary` varchar(1000) NOT NULL,
    `enabled` tinyint(1) NOT NULL DEFAULT 1,
    `sort_order` int NOT NULL DEFAULT 0,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `text_game_story_versions` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `story_id` bigint NOT NULL,
    `version_number` int NOT NULL,
    `status` varchar(16) NOT NULL,
    `revision` bigint NOT NULL DEFAULT 0,
    `story_json` json NOT NULL,
    `checksum` char(64) NOT NULL,
    `published_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_text_game_story_version` (`story_id`, `version_number`),
    KEY `idx_text_game_story_version_status` (`story_id`, `status`),
    CONSTRAINT `fk_text_game_story_versions_story`
        FOREIGN KEY (`story_id`) REFERENCES `text_game_stories` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `text_game_sessions` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `session_id` char(36) NOT NULL,
    `story_version_id` bigint NOT NULL,
    `current_node_id` varchar(128) NOT NULL,
    `pending_node_id` varchar(128),
    `phase` varchar(16) NOT NULL,
    `attributes_json` json NOT NULL,
    `relations_json` json NOT NULL,
    `flags_json` json NOT NULL,
    `history_json` json NOT NULL,
    `result_json` json,
    `revision` bigint NOT NULL DEFAULT 0,
    `expires_at` datetime(6) NOT NULL,
    `completed_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_text_game_sessions_session_id` (`session_id`),
    KEY `idx_text_game_sessions_expiry` (`expires_at`),
    KEY `idx_text_game_sessions_story_version` (`story_version_id`),
    CONSTRAINT `fk_text_game_sessions_story_version`
        FOREIGN KEY (`story_version_id`) REFERENCES `text_game_story_versions` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `text_game_session_events` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `session_id` char(36) NOT NULL,
    `sequence_no` int NOT NULL,
    `node_id` varchar(128) NOT NULL,
    `choice_id` varchar(128) NOT NULL,
    `effects_json` json NOT NULL,
    `state_after_json` json NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_text_game_session_event_sequence` (`session_id`, `sequence_no`),
    CONSTRAINT `fk_text_game_session_events_session`
        FOREIGN KEY (`session_id`) REFERENCES `text_game_sessions` (`session_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Module: marketplace
-- Complete schema for the marketplace module.
-- Loaded at application startup; existing database upgrades are manual under db/upgrade/marketplace/.
-- CREATE TABLE IF NOT EXISTS does not add missing columns to existing tables.

-- -----------------------------------------------------------------------------
-- Marketplace users, listings, conversations, and messages
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `marketplace_users` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `username` varchar(64) NOT NULL UNIQUE,
    `password_hash` varchar(255) NOT NULL,
    `display_name` varchar(80) NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `marketplace_sessions` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `token_hash` char(64) NOT NULL,
    `user_id` bigint NOT NULL,
    `expires_at` datetime(6) NOT NULL,
    `revoked_at` datetime(6),
    `last_seen_at` datetime(6) NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_marketplace_sessions_token_hash` (`token_hash`),
    KEY `idx_marketplace_sessions_user` (`user_id`, `created_at`),
    KEY `idx_marketplace_sessions_expires_at` (`expires_at`),
    CONSTRAINT `fk_marketplace_sessions_user`
        FOREIGN KEY (`user_id`) REFERENCES `marketplace_users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `marketplace_categories` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `name` varchar(80) NOT NULL,
    `slug` varchar(80) NOT NULL UNIQUE,
    `sort_order` int NOT NULL DEFAULT 0,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '手机数码', 'phones-digital', 10
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'phones-digital');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '家用电器', 'home-appliances', 20
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'home-appliances');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '家具家居', 'furniture-home', 30
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'furniture-home');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '服饰鞋包', 'fashion-bags', 40
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'fashion-bags');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '图书文具', 'books-stationery', 50
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'books-stationery');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '运动户外', 'sports-outdoor', 60
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'sports-outdoor');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '其他', 'other', 70
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'other');

CREATE TABLE IF NOT EXISTS `marketplace_items` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `seller_id` bigint NOT NULL,
    `category_id` bigint NOT NULL,
    `title` varchar(160) NOT NULL,
    `description` text NOT NULL,
    `image_url` varchar(1000) NOT NULL,
    `price` decimal(12,2),
    `status` varchar(16) NOT NULL DEFAULT 'LISTED',
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY `idx_marketplace_items_status_created` (`status`, `created_at`),
    KEY `idx_marketplace_items_category_status` (`category_id`, `status`, `created_at`),
    KEY `idx_marketplace_items_seller` (`seller_id`, `created_at`),
    CONSTRAINT `fk_marketplace_items_seller`
        FOREIGN KEY (`seller_id`) REFERENCES `marketplace_users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_marketplace_items_category`
        FOREIGN KEY (`category_id`) REFERENCES `marketplace_categories` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `marketplace_conversations` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `item_id` bigint NOT NULL,
    `buyer_id` bigint NOT NULL,
    `seller_id` bigint NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_marketplace_conversation_item_buyer` (`item_id`, `buyer_id`),
    KEY `idx_marketplace_conversations_buyer` (`buyer_id`, `updated_at`),
    KEY `idx_marketplace_conversations_seller` (`seller_id`, `updated_at`),
    CONSTRAINT `fk_marketplace_conversations_item`
        FOREIGN KEY (`item_id`) REFERENCES `marketplace_items` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_marketplace_conversations_buyer`
        FOREIGN KEY (`buyer_id`) REFERENCES `marketplace_users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_marketplace_conversations_seller`
        FOREIGN KEY (`seller_id`) REFERENCES `marketplace_users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `marketplace_messages` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `conversation_id` bigint NOT NULL,
    `sender_id` bigint NOT NULL,
    `body` text NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY `idx_marketplace_messages_conversation` (`conversation_id`, `id`),
    CONSTRAINT `fk_marketplace_messages_conversation`
        FOREIGN KEY (`conversation_id`) REFERENCES `marketplace_conversations` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_marketplace_messages_sender`
        FOREIGN KEY (`sender_id`) REFERENCES `marketplace_users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Module: weibo
-- Complete schema for the weibo module.
-- Loaded at application startup; existing database upgrades are manual under db/upgrade/weibo/.
-- CREATE TABLE IF NOT EXISTS does not add missing columns to existing tables.
-- Numeric id columns are database-generated; unique business keys retain OAuth, review and retry identities.

-- -----------------------------------------------------------------------------
-- Weibo OAuth state and account credentials
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `weibo_oauth_state` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `state` varchar(128) NOT NULL,
    `expires_at` datetime(6) NOT NULL,
    `used_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_weibo_oauth_state` (`state`),
    KEY `idx_weibo_oauth_state_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `weibo_account_token` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `uid` varchar(64) NOT NULL,
    `access_token` text NOT NULL,
    `expires_at` datetime(6) NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_weibo_account_token_uid` (`uid`),
    KEY `idx_weibo_account_token_updated_at` (`updated_at`),
    KEY `idx_weibo_account_token_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Weibo drafts, human-review history, and publishing attempts.
-- Never alter approval/state rows to bypass human review.
CREATE TABLE IF NOT EXISTS weibo_account_gate (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uid varchar(64) NOT NULL,
    UNIQUE KEY uk_weibo_account_gate_uid (uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_post (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_key varchar(36) NOT NULL,
    target_uid varchar(64) NOT NULL,
    event_key varchar(64) NOT NULL,
    event_json longtext NOT NULL,
    body longtext,
    review_note text,
    content_version int NOT NULL,
    revision bigint NOT NULL,
    status varchar(32) NOT NULL,
    created_at datetime(6) NOT NULL,
    updated_at datetime(6) NOT NULL,
    expires_at datetime(6) NOT NULL,
    reviewer varchar(128),
    reviewed_at datetime(6),
    review_reason text,
    attempt_id varchar(36),
    publish_started_at datetime(6),
    weibo_id varchar(128),
    last_error varchar(1000),
    UNIQUE KEY uk_weibo_post_key (post_key),
    UNIQUE KEY uk_weibo_post_event (target_uid, event_key),
    KEY idx_weibo_post_actionable (status, updated_at),
    KEY idx_weibo_post_daily (target_uid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_post_history (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_key varchar(36) NOT NULL,
    revision bigint NOT NULL,
    content_version int NOT NULL,
    status varchar(32) NOT NULL,
    body longtext,
    reviewer varchar(128),
    review_reason text,
    attempt_id varchar(36),
    weibo_id varchar(128),
    last_error varchar(1000),
    updated_at datetime(6) NOT NULL,
    UNIQUE KEY uk_weibo_post_history_revision (post_key, revision),
    CONSTRAINT fk_weibo_post_history FOREIGN KEY (post_key) REFERENCES weibo_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_publish_attempt (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attempt_id varchar(36) NOT NULL,
    post_id varchar(36) NOT NULL,
    target_uid varchar(64) NOT NULL,
    content_version int NOT NULL,
    status varchar(32) NOT NULL,
    started_at datetime(6) NOT NULL,
    completed_at datetime(6),
    weibo_id varchar(128),
    last_error varchar(1000),
    UNIQUE KEY uk_weibo_attempt_id (attempt_id),
    UNIQUE KEY uk_weibo_attempt_version (post_id, content_version),
    KEY idx_weibo_attempt_daily (target_uid, started_at),
    CONSTRAINT fk_weibo_publish_attempt FOREIGN KEY (post_id) REFERENCES weibo_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Telegram review deliveries are created only after the Weibo workflow tables above.
-- Existing databases also require db/upgrade/weibo/migration_add_auto_increment_primary_keys.sql.
CREATE TABLE IF NOT EXISTS weibo_review_delivery (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    delivery_key varchar(36) NOT NULL,
    bot_id bigint NOT NULL,
    post_id varchar(36) NOT NULL,
    content_version int NOT NULL,
    chat_id bigint NOT NULL,
    message_id bigint,
    status varchar(16) NOT NULL,
    expires_at datetime(6) NOT NULL,
    callback_id varchar(128),
    approved boolean,
    reviewer varchar(128),
    decided_at datetime(6),
    decision_status varchar(16),
    UNIQUE KEY uk_weibo_review_delivery_key (delivery_key),
    UNIQUE KEY uk_weibo_review_delivery_version (bot_id, post_id, content_version),
    CONSTRAINT fk_weibo_review_delivery_post FOREIGN KEY (post_id) REFERENCES weibo_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_review_polling (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    bot_id bigint NOT NULL,
    next_offset bigint NOT NULL DEFAULT 0,
    lease_owner varchar(64),
    lease_until datetime(6),
    UNIQUE KEY uk_weibo_review_polling_bot (bot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Module: x
-- Complete schema for the x module.
-- Loaded at application startup; existing database upgrades are manual under db/upgrade/x/.
-- CREATE TABLE IF NOT EXISTS does not add missing columns to existing tables.

-- Independent X workflow; existing old X tables also require the manual
-- db/upgrade/x/migration_add_auto_increment_primary_keys.sql upgrade.
CREATE TABLE IF NOT EXISTS x_account_gate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id varchar(64) NOT NULL,
    UNIQUE KEY uk_x_account_gate_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_post (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_key varchar(36) NOT NULL,
    target_user_id varchar(64) NOT NULL,
    generation_key varchar(128) NOT NULL,
    content_policy_json longtext NOT NULL,
    body longtext,
    review_note text,
    content_version int NOT NULL,
    revision bigint NOT NULL,
    status varchar(32) NOT NULL,
    created_at datetime(6) NOT NULL,
    updated_at datetime(6) NOT NULL,
    expires_at datetime(6) NOT NULL,
    reviewer varchar(128),
    reviewed_at datetime(6),
    review_reason text,
    attempt_id varchar(36),
    publish_started_at datetime(6),
    external_post_id varchar(128),
    last_error varchar(1000),
    UNIQUE KEY uk_x_post_key (post_key),
    UNIQUE KEY uk_x_post_generation (target_user_id, generation_key),
    KEY idx_x_post_actionable (status, updated_at),
    KEY idx_x_post_daily (target_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_post_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_key varchar(36) NOT NULL,
    revision bigint NOT NULL,
    content_version int NOT NULL,
    status varchar(32) NOT NULL,
    body longtext,
    reviewer varchar(128),
    review_reason text,
    attempt_id varchar(36),
    external_post_id varchar(128),
    last_error varchar(1000),
    updated_at datetime(6) NOT NULL,
    UNIQUE KEY uk_x_post_history_revision (post_key, revision),
    CONSTRAINT fk_x_post_history FOREIGN KEY (post_key) REFERENCES x_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_publish_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attempt_id varchar(36) NOT NULL,
    post_id varchar(36) NOT NULL,
    target_user_id varchar(64) NOT NULL,
    content_version int NOT NULL,
    status varchar(32) NOT NULL,
    started_at datetime(6) NOT NULL,
    completed_at datetime(6),
    external_post_id varchar(128),
    last_error varchar(1000),
    UNIQUE KEY uk_x_attempt_id (attempt_id),
    UNIQUE KEY uk_x_attempt_version (post_id, content_version),
    KEY idx_x_attempt_daily (target_user_id, started_at),
    CONSTRAINT fk_x_publish_attempt FOREIGN KEY (post_id) REFERENCES x_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_review_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    delivery_key varchar(36) NOT NULL,
    bot_id bigint NOT NULL,
    post_id varchar(36) NOT NULL,
    content_version int NOT NULL,
    chat_id bigint NOT NULL,
    message_id bigint,
    status varchar(16) NOT NULL,
    expires_at datetime(6) NOT NULL,
    callback_id varchar(128),
    approved boolean,
    reviewer varchar(128),
    decided_at datetime(6),
    decision_status varchar(16),
    UNIQUE KEY uk_x_review_delivery_key (delivery_key),
    UNIQUE KEY uk_x_review_delivery_version (bot_id, post_id, content_version),
    CONSTRAINT fk_x_review_delivery_post FOREIGN KEY (post_id) REFERENCES x_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_review_polling (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    bot_id bigint NOT NULL,
    next_offset bigint NOT NULL DEFAULT 0,
    lease_owner varchar(64),
    lease_until datetime(6),
    UNIQUE KEY uk_x_review_polling_bot (bot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
