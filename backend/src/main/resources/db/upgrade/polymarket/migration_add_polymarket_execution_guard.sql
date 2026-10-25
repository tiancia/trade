-- Applies to databases that have Polymarket decision audits but no execution guard table.
-- Stop all Polymarket workers and keep execution disabled while applying this additive DDL.
-- Review prior unresolved executions before re-enabling submission; no old audit is assumed safe.
-- MySQL DDL commits implicitly. This script does not delete, reset, or automatically release any guard.
-- Verify after application:
-- SHOW CREATE TABLE polymarket_execution_guard;
-- SELECT id, guard_name, pending_decision_id, updated_at FROM polymarket_execution_guard;
-- A pending_decision_id requires manual reconciliation against exchange orders, fills and assets;
-- restarting the application or rerunning this script must not clear it.

CREATE TABLE IF NOT EXISTS `polymarket_execution_guard` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `guard_name` varchar(64) NOT NULL UNIQUE,
    `pending_decision_id` bigint,
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
