CREATE TABLE IF NOT EXISTS automation_module_switch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    module_id VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_automation_module_switch (module_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
