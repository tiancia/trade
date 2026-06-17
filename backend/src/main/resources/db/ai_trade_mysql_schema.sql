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
    `session_id` char(36) NOT NULL PRIMARY KEY,
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

CREATE TABLE IF NOT EXISTS `weibo_oauth_state` (
    `state` varchar(128) NOT NULL PRIMARY KEY,
    `expires_at` datetime(6) NOT NULL,
    `used_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY `idx_weibo_oauth_state_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `weibo_account_token` (
    `uid` varchar(64) NOT NULL PRIMARY KEY,
    `access_token` text NOT NULL,
    `expires_at` datetime(6) NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY `idx_weibo_account_token_updated_at` (`updated_at`),
    KEY `idx_weibo_account_token_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
