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
