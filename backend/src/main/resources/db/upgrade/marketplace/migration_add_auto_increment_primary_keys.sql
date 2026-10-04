-- Upgrade the previous marketplace schema, where marketplace_sessions.token_hash was the primary key.
-- Preconditions: stop all application writers, back up the database, and verify the old schema.
-- Apply once, before deploying the updated schema. Do not apply to a newly initialized database.
-- MySQL DDL commits implicitly: this script is not transactionally reversible. After a partial
-- failure, inspect the actual schema before continuing; rollback requires a verified backup.
-- Existing token hashes and user associations are retained; existing rows receive generated ids.

ALTER TABLE `marketplace_sessions`
    ADD UNIQUE KEY `uk_marketplace_sessions_token_hash` (`token_hash`);

ALTER TABLE `marketplace_sessions`
    DROP PRIMARY KEY,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

-- Verification: SHOW CREATE TABLE marketplace_sessions;
-- Compare session counts and user associations with the backup. PRIMARY KEY must be (id),
-- id must be AUTO_INCREMENT, and token_hash must remain UNIQUE for authentication and revocation.
