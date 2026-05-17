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
