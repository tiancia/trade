-- Upgrade databases with an existing migration history. Stop all old writers and
-- verify the release backup first. The four retired game tables cannot be recovered
-- from this migration. Marketplace rows stay available for export to its new database.
-- Verify the story switch and text_game_* tables are absent after upgrading.
DELETE FROM automation_module_switch WHERE module_id = 'story';
DROP TABLE IF EXISTS text_game_session_events;
DROP TABLE IF EXISTS text_game_sessions;
DROP TABLE IF EXISTS text_game_story_versions;
DROP TABLE IF EXISTS text_game_stories;
