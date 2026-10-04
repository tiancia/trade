-- Upgrade the previous textgame schema, where text_game_sessions.session_id was the primary key.
-- Preconditions: stop all application writers, back up the database, and verify the old schema.
-- Apply once, before deploying the updated schema. Do not apply to a newly initialized database.
-- MySQL DDL commits implicitly: this script is not transactionally reversible. After a partial
-- failure, inspect the actual schema before continuing; rollback requires a verified backup.
-- Existing UUIDs and event references are retained. Existing rows receive database-generated ids.

ALTER TABLE `text_game_sessions`
    ADD UNIQUE KEY `uk_text_game_sessions_session_id` (`session_id`);

ALTER TABLE `text_game_session_events`
    DROP FOREIGN KEY `fk_text_game_session_events_session`;

ALTER TABLE `text_game_sessions`
    DROP PRIMARY KEY,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE `text_game_session_events`
    ADD CONSTRAINT `fk_text_game_session_events_session`
        FOREIGN KEY (`session_id`) REFERENCES `text_game_sessions` (`session_id`) ON DELETE CASCADE;

-- Verification: SHOW CREATE TABLE text_game_sessions;
-- Compare session/event counts and UUID associations with the backup. PRIMARY KEY must be (id),
-- id must be AUTO_INCREMENT, and session_id must remain UNIQUE with the event foreign key intact.
