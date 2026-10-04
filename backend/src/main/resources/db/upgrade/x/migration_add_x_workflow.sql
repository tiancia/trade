-- Independent X draft/review/publishing workflow; no existing tables or data are changed.
-- Prerequisite: MySQL 8 with InnoDB; this module has no dependency on Weibo tables or OAuth.
-- Apply manually to a backed-up test database first. The application does not auto-run migration files.
-- Verify: SHOW TABLES LIKE 'x_%'; inspect the six new tables, unique keys, and foreign keys.
-- Verify: SELECT target_user_id, generation_key, COUNT(*) FROM x_post
--         GROUP BY target_user_id, generation_key HAVING COUNT(*) > 1;
-- Creation is idempotent; there are no destructive operations. Credentials are not stored here.
CREATE TABLE IF NOT EXISTS x_account_gate (
    user_id varchar(64) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_post (
    id varchar(36) NOT NULL PRIMARY KEY,
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
    UNIQUE KEY uk_x_post_generation (target_user_id, generation_key),
    KEY idx_x_post_actionable (status, updated_at),
    KEY idx_x_post_daily (target_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_post_history (
    id varchar(36) NOT NULL,
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
    PRIMARY KEY (id, revision),
    CONSTRAINT fk_x_post_history FOREIGN KEY (id) REFERENCES x_post(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_publish_attempt (
    attempt_id varchar(36) NOT NULL PRIMARY KEY,
    post_id varchar(36) NOT NULL,
    target_user_id varchar(64) NOT NULL,
    content_version int NOT NULL,
    status varchar(32) NOT NULL,
    started_at datetime(6) NOT NULL,
    completed_at datetime(6),
    external_post_id varchar(128),
    last_error varchar(1000),
    UNIQUE KEY uk_x_attempt_version (post_id, content_version),
    KEY idx_x_attempt_daily (target_user_id, started_at),
    CONSTRAINT fk_x_publish_attempt FOREIGN KEY (post_id) REFERENCES x_post(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_review_delivery (
    id varchar(36) NOT NULL PRIMARY KEY,
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
    UNIQUE KEY uk_x_review_delivery_version (bot_id, post_id, content_version),
    CONSTRAINT fk_x_review_delivery_post FOREIGN KEY (post_id) REFERENCES x_post(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS x_review_polling (
    bot_id bigint NOT NULL PRIMARY KEY,
    next_offset bigint NOT NULL DEFAULT 0,
    lease_owner varchar(64),
    lease_until datetime(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
