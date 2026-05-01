CREATE TABLE IF NOT EXISTS `decision_runs` (
    `decision_id` char(36) PRIMARY KEY,
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
    `last_price` decimal(38,18),
    `available_base` decimal(38,18),
    `available_quote` decimal(38,18),
    `execution_status` varchar(64),
    `skip_reason` text,
    `error` text,
    KEY `idx_decision_runs_started_at` (`started_at`),
    KEY `idx_decision_runs_inst_action` (`inst_id`, `action`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_requests` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_id` char(36) NOT NULL UNIQUE,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `prompt_text` text,
    `ai_parameters_json` json,
    CONSTRAINT `fk_ai_requests_decision`
        FOREIGN KEY (`decision_id`) REFERENCES `decision_runs` (`decision_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_responses` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_id` char(36) NOT NULL UNIQUE,
    `received_at` datetime(6) NOT NULL,
    `raw_response` text,
    `parsed_action` varchar(16),
    `parsed_reason` text,
    `parsed_buy_quote_amount` decimal(38,18),
    `parsed_sell_base_amount` decimal(38,18),
    CONSTRAINT `fk_ai_responses_decision`
        FOREIGN KEY (`decision_id`) REFERENCES `decision_runs` (`decision_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `order_executions` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_id` char(36) NOT NULL UNIQUE,
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
    KEY `idx_order_executions_order_id` (`order_id`),
    CONSTRAINT `fk_order_executions_decision`
        FOREIGN KEY (`decision_id`) REFERENCES `decision_runs` (`decision_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
