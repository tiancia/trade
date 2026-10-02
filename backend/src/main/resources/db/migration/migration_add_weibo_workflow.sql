-- Reviewed Weibo workflow; existing OAuth tables are unchanged.
-- Prerequisite: migration_add_weibo_oauth.sql on existing databases.
-- Apply to a backed-up test database first. Idempotent table creation only; no data removal.
-- Verify: SHOW TABLES LIKE 'weibo_%'; inspect all four new tables and their indexes.
-- Migration scripts are manual; never alter approval/state rows to bypass human review.
CREATE TABLE IF NOT EXISTS weibo_account_gate (
    uid varchar(64) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_post (
    id varchar(36) NOT NULL PRIMARY KEY,
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
    UNIQUE KEY uk_weibo_post_event (target_uid, event_key),
    KEY idx_weibo_post_actionable (status, updated_at),
    KEY idx_weibo_post_daily (target_uid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_post_history (
    id varchar(36) NOT NULL,
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
    PRIMARY KEY (id, revision),
    CONSTRAINT fk_weibo_post_history FOREIGN KEY (id) REFERENCES weibo_post(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_publish_attempt (
    attempt_id varchar(36) NOT NULL PRIMARY KEY,
    post_id varchar(36) NOT NULL,
    target_uid varchar(64) NOT NULL,
    content_version int NOT NULL,
    status varchar(32) NOT NULL,
    started_at datetime(6) NOT NULL,
    completed_at datetime(6),
    weibo_id varchar(128),
    last_error varchar(1000),
    UNIQUE KEY uk_weibo_attempt_version (post_id, content_version),
    KEY idx_weibo_attempt_daily (target_uid, started_at),
    CONSTRAINT fk_weibo_publish_attempt FOREIGN KEY (post_id) REFERENCES weibo_post(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
