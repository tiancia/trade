-- Applies to existing trading installations missing the two advisor tables.
-- Stop AI tasks and back up the database before running. Re-runnable CREATE IF NOT EXISTS.
-- Verify SHOW CREATE TABLE for both tables, generated BIGINT primary keys and business UNIQUE keys.
-- Does not alter trading, strategy configuration or X workflow data.
-- Rollback: disable AI auto-start/auto-apply, retain these audit tables; no destructive rollback required.
-- Independent AI advisor audit and durable rate limits. No credentials are stored.
CREATE TABLE IF NOT EXISTS okx_ai_strategy_guard (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_key VARCHAR(191) NOT NULL,
    next_review_at TIMESTAMP(6) NOT NULL,
    notification_after TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_ai_strategy_guard_scope (scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS okx_ai_strategy_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    review_id VARCHAR(64) NOT NULL,
    scope_key VARCHAR(191) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    snapshot_json LONGTEXT NULL,
    proposal_json LONGTEXT NULL,
    outcome VARCHAR(32) NOT NULL,
    detail VARCHAR(1000) NULL,
    notification_status VARCHAR(32) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_ai_strategy_review_id (review_id),
    KEY idx_ai_strategy_review_scope (scope_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
