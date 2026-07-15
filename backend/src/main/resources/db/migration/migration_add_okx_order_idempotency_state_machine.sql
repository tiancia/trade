-- Adds the operational order ledger used before calling OKX.
-- Prerequisite: the existing OKX trading schema is already present.
-- Verify after applying:
--   SHOW INDEX FROM okx_orders;
--   SELECT status, COUNT(*) FROM okx_orders GROUP BY status;

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
