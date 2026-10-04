-- One-time manual upgrade for the Weibo tables created before numeric primary keys.
-- Stop the application and back up the database before running this script.
-- Requires all eight tables, including migration_add_weibo_review_delivery.sql.
-- Existing UUIDs, OAuth states, account identities, histories, decisions and cursors are preserved.
-- MySQL DDL commits implicitly. Do not rerun after a partial failure without inspecting the schema.

ALTER TABLE weibo_post_history DROP FOREIGN KEY fk_weibo_post_history;
ALTER TABLE weibo_publish_attempt DROP FOREIGN KEY fk_weibo_publish_attempt;
ALTER TABLE weibo_review_delivery DROP FOREIGN KEY fk_weibo_review_delivery_post;

ALTER TABLE weibo_oauth_state
    ADD UNIQUE KEY uk_weibo_oauth_state (state),
    DROP PRIMARY KEY,
    ADD COLUMN id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE weibo_account_token
    ADD UNIQUE KEY uk_weibo_account_token_uid (uid),
    DROP PRIMARY KEY,
    ADD COLUMN id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE weibo_account_gate
    ADD UNIQUE KEY uk_weibo_account_gate_uid (uid),
    DROP PRIMARY KEY,
    ADD COLUMN id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE weibo_post
    CHANGE COLUMN id post_key varchar(36) NOT NULL,
    ADD UNIQUE KEY uk_weibo_post_key (post_key),
    DROP PRIMARY KEY,
    ADD COLUMN id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE weibo_post_history
    CHANGE COLUMN id post_key varchar(36) NOT NULL,
    ADD UNIQUE KEY uk_weibo_post_history_revision (post_key, revision),
    DROP PRIMARY KEY,
    ADD COLUMN id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE weibo_publish_attempt
    ADD UNIQUE KEY uk_weibo_attempt_id (attempt_id),
    DROP PRIMARY KEY,
    ADD COLUMN id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE weibo_review_delivery
    CHANGE COLUMN id delivery_key varchar(36) NOT NULL,
    ADD UNIQUE KEY uk_weibo_review_delivery_key (delivery_key),
    DROP PRIMARY KEY,
    ADD COLUMN id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE weibo_review_polling
    ADD UNIQUE KEY uk_weibo_review_polling_bot (bot_id),
    DROP PRIMARY KEY,
    ADD COLUMN id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE weibo_post_history
    ADD CONSTRAINT fk_weibo_post_history FOREIGN KEY (post_key) REFERENCES weibo_post(post_key);
ALTER TABLE weibo_publish_attempt
    ADD CONSTRAINT fk_weibo_publish_attempt FOREIGN KEY (post_id) REFERENCES weibo_post(post_key);
ALTER TABLE weibo_review_delivery
    ADD CONSTRAINT fk_weibo_review_delivery_post FOREIGN KEY (post_id) REFERENCES weibo_post(post_key);
