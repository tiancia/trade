-- Durable Telegram delivery, first callback decision, and per-bot polling lease/cursor.
-- Prerequisites: migration_add_weibo_oauth.sql, then migration_add_weibo_workflow.sql.
-- Apply to a backed-up test database first. Creates two tables; no historical data is changed or removed.
-- Verify: SHOW CREATE TABLE weibo_review_delivery; SHOW CREATE TABLE weibo_review_polling;
-- Verify: SELECT bot_id, post_id, content_version, COUNT(*) FROM weibo_review_delivery
--         GROUP BY bot_id, post_id, content_version HAVING COUNT(*) > 1;
-- Both decisions and leases use microsecond timestamps. No bot token is persisted.
CREATE TABLE IF NOT EXISTS weibo_review_delivery (
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
    UNIQUE KEY uk_weibo_review_delivery_version (bot_id, post_id, content_version),
    CONSTRAINT fk_weibo_review_delivery_post FOREIGN KEY (post_id) REFERENCES weibo_post(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS weibo_review_polling (
    bot_id bigint NOT NULL PRIMARY KEY,
    next_offset bigint NOT NULL DEFAULT 0,
    lease_owner varchar(64),
    lease_until datetime(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
