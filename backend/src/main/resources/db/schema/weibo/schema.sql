-- Complete schema for the weibo module.
-- Loaded at application startup; existing database upgrades are manual under db/upgrade/weibo/.
-- CREATE TABLE IF NOT EXISTS does not add missing columns to existing tables.
-- Numeric id columns are database-generated; unique business keys retain OAuth, review and retry identities.

-- -----------------------------------------------------------------------------
-- Weibo OAuth state and account credentials
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `weibo_oauth_state` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `state` varchar(128) NOT NULL,
    `expires_at` datetime(6) NOT NULL,
    `used_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_weibo_oauth_state` (`state`),
    KEY `idx_weibo_oauth_state_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `weibo_account_token` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `uid` varchar(64) NOT NULL,
    `access_token` text NOT NULL,
    `expires_at` datetime(6) NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_weibo_account_token_uid` (`uid`),
    KEY `idx_weibo_account_token_updated_at` (`updated_at`),
    KEY `idx_weibo_account_token_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Weibo drafts, human-review history, and publishing attempts.
-- Never alter approval/state rows to bypass human review.
CREATE TABLE IF NOT EXISTS weibo_account_gate (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uid varchar(64) NOT NULL,
    UNIQUE KEY uk_weibo_account_gate_uid (uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_post (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_key varchar(36) NOT NULL,
    target_uid varchar(64) NOT NULL,
    event_key varchar(64) NOT NULL,
    event_json longtext NOT NULL,
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
    weibo_id varchar(128),
    last_error varchar(1000),
    UNIQUE KEY uk_weibo_post_key (post_key),
    UNIQUE KEY uk_weibo_post_event (target_uid, event_key),
    KEY idx_weibo_post_actionable (status, updated_at),
    KEY idx_weibo_post_daily (target_uid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_post_history (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_key varchar(36) NOT NULL,
    revision bigint NOT NULL,
    content_version int NOT NULL,
    status varchar(32) NOT NULL,
    body longtext,
    reviewer varchar(128),
    review_reason text,
    attempt_id varchar(36),
    weibo_id varchar(128),
    last_error varchar(1000),
    updated_at datetime(6) NOT NULL,
    UNIQUE KEY uk_weibo_post_history_revision (post_key, revision),
    CONSTRAINT fk_weibo_post_history FOREIGN KEY (post_key) REFERENCES weibo_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_publish_attempt (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attempt_id varchar(36) NOT NULL,
    post_id varchar(36) NOT NULL,
    target_uid varchar(64) NOT NULL,
    content_version int NOT NULL,
    status varchar(32) NOT NULL,
    started_at datetime(6) NOT NULL,
    completed_at datetime(6),
    weibo_id varchar(128),
    last_error varchar(1000),
    UNIQUE KEY uk_weibo_attempt_id (attempt_id),
    UNIQUE KEY uk_weibo_attempt_version (post_id, content_version),
    KEY idx_weibo_attempt_daily (target_uid, started_at),
    CONSTRAINT fk_weibo_publish_attempt FOREIGN KEY (post_id) REFERENCES weibo_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Telegram review deliveries are created only after the Weibo workflow tables above.
-- Existing databases also require db/upgrade/weibo/migration_add_auto_increment_primary_keys.sql.
CREATE TABLE IF NOT EXISTS weibo_review_delivery (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
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
    UNIQUE KEY uk_weibo_review_delivery_key (delivery_key),
    UNIQUE KEY uk_weibo_review_delivery_version (bot_id, post_id, content_version),
    CONSTRAINT fk_weibo_review_delivery_post FOREIGN KEY (post_id) REFERENCES weibo_post(post_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_review_polling (
    id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    bot_id bigint NOT NULL,
    next_offset bigint NOT NULL DEFAULT 0,
    lease_owner varchar(64),
    lease_until datetime(6),
    UNIQUE KEY uk_weibo_review_polling_bot (bot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
