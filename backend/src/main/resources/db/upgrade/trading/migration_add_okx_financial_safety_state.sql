-- Adds MySQL-authoritative position/cost/risk state and the idempotent fill ledger.
-- Prerequisite: migration_add_okx_order_idempotency_state_machine.sql.
-- Existing data is not overwritten. On first application startup, an otherwise
-- empty scope is seeded from the legacy data/trading-state.json values.
-- Historical terminal orders are intentionally not inserted into the new fill
-- ledger: the legacy JSON position may already include them, and automatic
-- replay would double-count capital. Treat the imported position as the
-- pre-upgrade baseline and reconcile any suspected old crash gap manually.
-- Verify after applying:
--   SELECT * FROM okx_position_state;
--   SELECT * FROM okx_risk_state;
--   SELECT * FROM okx_fund_safety_state;
--   SELECT order_id, cumulative_filled_size, applied_position_quantity
--     FROM okx_order_fill_ledger ORDER BY updated_at DESC LIMIT 20;

CREATE TABLE IF NOT EXISTS `okx_position_state` (
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
    PRIMARY KEY (`account_scope`, `inst_id`),
    KEY `idx_okx_position_state_reconciled` (`last_reconciled_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_risk_state` (
    `account_scope` varchar(32) NOT NULL PRIMARY KEY,
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
    `updated_at` datetime(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `okx_fund_safety_state` (
    `account_scope` varchar(32) NOT NULL PRIMARY KEY,
    `status` varchar(16) NOT NULL,
    `reason` text,
    `source` varchar(64),
    `resume_reason` text,
    `last_action_error` text,
    `halted_at` datetime(6),
    `resumed_at` datetime(6),
    `updated_at` datetime(6) NOT NULL,
    `version` bigint NOT NULL DEFAULT 0,
    KEY `idx_okx_fund_safety_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A new LIVE scope is fail-safe. A successful reconciliation plus the
-- token-protected resume flow is required before real submissions can start.
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

CREATE TABLE IF NOT EXISTS `okx_order_fill_ledger` (
    `order_id` bigint NOT NULL PRIMARY KEY,
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
    KEY `idx_okx_order_fill_ledger_exchange_updated` (`exchange_updated_at`),
    CONSTRAINT `fk_okx_order_fill_ledger_order`
        FOREIGN KEY (`order_id`) REFERENCES `okx_orders` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
