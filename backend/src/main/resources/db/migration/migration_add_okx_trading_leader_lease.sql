-- Adds database-backed single-writer leadership for trading automation.
--
-- Applies to:
--   Existing databases that already contain the OKX trading tables but do not
--   yet contain okx_trading_leader_lease.
--
-- Preconditions:
--   1. Stop every trading automation instance.
--   2. Keep TRADE_TRADING_LIVE_ENABLED=false.
--   3. Back up the target schema.
--
-- Verification:
--   SHOW CREATE TABLE okx_trading_leader_lease;
--   SELECT * FROM okx_trading_leader_lease;
--
-- Rollback:
--   Disable TRADE_TRADING_LEADERSHIP_ENABLED first. The table can then be
--   dropped only if no application instance is using it.

CREATE TABLE IF NOT EXISTS `okx_trading_leader_lease` (
    `lease_name` varchar(128) NOT NULL PRIMARY KEY,
    `owner_id` varchar(128) NOT NULL,
    `lease_until` datetime(6) NOT NULL,
    `fencing_token` bigint NOT NULL DEFAULT 1,
    `created_at` datetime(6) NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    KEY `idx_okx_trading_leader_lease_expiry` (`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
