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
    `run_id` varchar(64) NOT NULL PRIMARY KEY,
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
    `run_id` varchar(64) NOT NULL PRIMARY KEY,
    `total_return` decimal(38,18),
    `max_drawdown` decimal(38,18),
    `win_rate` decimal(38,18),
    `profit_factor` decimal(38,18),
    `trade_count` int,
    `final_equity` decimal(38,18),
    CONSTRAINT `fk_okx_backtest_metrics_run`
        FOREIGN KEY (`run_id`) REFERENCES `okx_backtest_runs` (`run_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_candle_cache` (
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
    PRIMARY KEY (`inst_id`, `bar`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
