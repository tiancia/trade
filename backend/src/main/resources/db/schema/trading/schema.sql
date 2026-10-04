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
