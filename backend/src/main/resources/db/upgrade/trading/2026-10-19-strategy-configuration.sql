-- Persistent effective strategy configuration and immutable version history.
-- Applies to existing trading databases; adds two independent tables only.
-- Back up and stop configuration writers before applying; MySQL DDL auto-commits.
-- Verify both tables have generated BIGINT id keys and strategy/version UNIQUE keys.
-- No historical decisions are rewritten. Keep these tables when rolling back the app.
CREATE TABLE IF NOT EXISTS okx_strategy_configuration (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    strategy_id VARCHAR(191) NOT NULL,
    strategy_type VARCHAR(100) NOT NULL,
    bar VARCHAR(32) NOT NULL,
    config_version BIGINT NOT NULL,
    params_json LONGTEXT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_strategy_configuration_id (strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS okx_strategy_configuration_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    strategy_id VARCHAR(191) NOT NULL,
    strategy_type VARCHAR(100) NOT NULL,
    bar VARCHAR(32) NOT NULL,
    config_version BIGINT NOT NULL,
    params_json LONGTEXT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_strategy_configuration_version (strategy_id, config_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
