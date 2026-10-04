-- Manual MySQL 8 migration for the OLD six-table X schema from migration_add_x_workflow.sql.
-- Do not run on the new baseline or run twice. CREATE TABLE IF NOT EXISTS does not perform this upgrade.
-- Before execution: stop all application instances / X generation, review and publishing workers;
-- back up all six X tables (structure and data), verify the backup can be restored, and rehearse
-- on an isolated copy. Check SHOW CREATE TABLE for each table and verify the old primary/FK names.
-- MySQL ALTER TABLE implicitly commits: a failure can leave a partially migrated database.
-- Keep the application stopped until all statements and validation succeed; do not blindly rerun.
-- Existing UUIDs, account keys, callback decisions, cursors and publication attempts are preserved.

ALTER TABLE x_post_history DROP FOREIGN KEY fk_x_post_history;
ALTER TABLE x_publish_attempt DROP FOREIGN KEY fk_x_publish_attempt;
ALTER TABLE x_review_delivery DROP FOREIGN KEY fk_x_review_delivery_post;

-- Rename each old id in a separate ALTER before adding the new numeric id. This prevents
-- MySQL from resolving the old business index against the new, same-named numeric column.
ALTER TABLE x_post CHANGE COLUMN id post_key varchar(36) NOT NULL;
ALTER TABLE x_post ADD UNIQUE KEY uk_x_post_key (post_key);
ALTER TABLE x_post
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id);

ALTER TABLE x_post_history CHANGE COLUMN id post_key varchar(36) NOT NULL;
ALTER TABLE x_post_history ADD UNIQUE KEY uk_x_post_history_revision (post_key, revision);
ALTER TABLE x_post_history
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id);

ALTER TABLE x_account_gate ADD UNIQUE KEY uk_x_account_gate_user (user_id);
ALTER TABLE x_account_gate
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id);

ALTER TABLE x_publish_attempt ADD UNIQUE KEY uk_x_attempt_id (attempt_id);
ALTER TABLE x_publish_attempt
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id);

ALTER TABLE x_review_delivery CHANGE COLUMN id delivery_key varchar(36) NOT NULL;
ALTER TABLE x_review_delivery ADD UNIQUE KEY uk_x_review_delivery_key (delivery_key);
ALTER TABLE x_review_delivery
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id);

ALTER TABLE x_review_polling ADD UNIQUE KEY uk_x_review_polling_bot (bot_id);
ALTER TABLE x_review_polling
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id);

ALTER TABLE x_post_history
    ADD CONSTRAINT fk_x_post_history FOREIGN KEY (post_key) REFERENCES x_post(post_key);
ALTER TABLE x_publish_attempt
    ADD CONSTRAINT fk_x_publish_attempt FOREIGN KEY (post_id) REFERENCES x_post(post_key);
ALTER TABLE x_review_delivery
    ADD CONSTRAINT fk_x_review_delivery_post FOREIGN KEY (post_id) REFERENCES x_post(post_key);

-- After execution: compare row counts and business keys with the backup; SHOW CREATE TABLE must
-- show id BIGINT AUTO_INCREMENT as the only primary key in every X table. Verify the original
-- unique constraints and all three FKs, and check for orphan history/attempt/delivery rows.
-- Rehearse new inserts without specifying numeric id and UUID-based read/CAS/review/polling flows
-- on the isolated copy before resuming workers with the matching application version.
-- Rollback is manual: while still stopped and before new writes, restore the validated backup
-- with the old application. After new writes, reconcile business changes first; restoring the
-- pre-migration backup alone would discard those writes. No automatic down migration is provided.
