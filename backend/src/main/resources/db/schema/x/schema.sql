-- Complete schema for the x module.
-- Loaded at application startup; existing database upgrades are manual under db/upgrade/x/.
-- CREATE TABLE IF NOT EXISTS does not add missing columns to existing tables.

-- Independent X workflow; existing old X tables also require the manual
-- db/upgrade/x/migration_add_auto_increment_primary_keys.sql upgrade.
CREATE TABLE IF NOT EXISTS x_account_gate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id varchar(64) NOT NULL,
    UNIQUE KEY uk_x_account_gate_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_post (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_key varchar(36) NOT NULL,
    target_user_id varchar(64) NOT NULL,
    generation_key varchar(128) NOT NULL,
    content_policy_json longtext NOT NULL,
    body longtext,
    review_note text,
    content_version int NOT NULL,
    revision bigint NOT NULL,
    status varchar(32) NOT NULL,
    created_at datetime(6) NOT NULL,
    updated_at datetime(6) NOT NULL,
    expires_at datetime(6) NOT NULL,
    reviewer varchar(128),
    reviewed_at datetime(6),
    review_reason text,
    attempt_id varchar(36),
    publish_started_at datetime(6),
    external_post_id varchar(128),
    last_error varchar(1000),
    UNIQUE KEY uk_x_post_key (post_key),
    UNIQUE KEY uk_x_post_generation (target_user_id, generation_key),
    KEY idx_x_post_actionable (status, updated_at),
    KEY idx_x_post_daily (target_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_post_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_key varchar(36) NOT NULL,
    revision bigint NOT NULL,
    content_version int NOT NULL,
    status varchar(32) NOT NULL,
    body longtext,
    reviewer varchar(128),
    review_reason text,
    attempt_id varchar(36),
    external_post_id varchar(128),
    last_error varchar(1000),
    updated_at datetime(6) NOT NULL,
    UNIQUE KEY uk_x_post_history_revision (post_key, revision),
    CONSTRAINT fk_x_post_history FOREIGN KEY (post_key) REFERENCES x_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_publish_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attempt_id varchar(36) NOT NULL,
    post_id varchar(36) NOT NULL,
    target_user_id varchar(64) NOT NULL,
    content_version int NOT NULL,
    status varchar(32) NOT NULL,
    started_at datetime(6) NOT NULL,
    completed_at datetime(6),
    external_post_id varchar(128),
    last_error varchar(1000),
    UNIQUE KEY uk_x_attempt_id (attempt_id),
    UNIQUE KEY uk_x_attempt_version (post_id, content_version),
    KEY idx_x_attempt_daily (target_user_id, started_at),
    CONSTRAINT fk_x_publish_attempt FOREIGN KEY (post_id) REFERENCES x_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_review_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    delivery_key varchar(36) NOT NULL,
    bot_id bigint NOT NULL,
    post_id varchar(36) NOT NULL,
    content_version int NOT NULL,
    chat_id bigint NOT NULL,
    message_id bigint,
    status varchar(16) NOT NULL,
    expires_at datetime(6) NOT NULL,
    callback_id varchar(128),
    approved boolean,
    reviewer varchar(128),
    decided_at datetime(6),
    decision_status varchar(16),
    UNIQUE KEY uk_x_review_delivery_key (delivery_key),
    UNIQUE KEY uk_x_review_delivery_version (bot_id, post_id, content_version),
    CONSTRAINT fk_x_review_delivery_post FOREIGN KEY (post_id) REFERENCES x_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_review_polling (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    bot_id bigint NOT NULL,
    next_offset bigint NOT NULL DEFAULT 0,
    lease_owner varchar(64),
    lease_until datetime(6),
    UNIQUE KEY uk_x_review_polling_bot (bot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
