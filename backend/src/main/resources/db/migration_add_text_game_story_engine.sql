CREATE TABLE IF NOT EXISTS `text_game_stories` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `story_key` varchar(128) NOT NULL UNIQUE,
    `title` varchar(255) NOT NULL,
    `summary` varchar(1000) NOT NULL,
    `enabled` tinyint(1) NOT NULL DEFAULT 1,
    `sort_order` int NOT NULL DEFAULT 0,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `text_game_story_versions` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `story_id` bigint NOT NULL,
    `version_number` int NOT NULL,
    `status` varchar(16) NOT NULL,
    `revision` bigint NOT NULL DEFAULT 0,
    `story_json` json NOT NULL,
    `checksum` char(64) NOT NULL,
    `published_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_text_game_story_version` (`story_id`, `version_number`),
    KEY `idx_text_game_story_version_status` (`story_id`, `status`),
    CONSTRAINT `fk_text_game_story_versions_story` FOREIGN KEY (`story_id`) REFERENCES `text_game_stories` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `text_game_sessions` (
    `session_id` char(36) NOT NULL PRIMARY KEY,
    `story_version_id` bigint NOT NULL,
    `current_node_id` varchar(128) NOT NULL,
    `pending_node_id` varchar(128),
    `phase` varchar(16) NOT NULL,
    `attributes_json` json NOT NULL,
    `relations_json` json NOT NULL,
    `flags_json` json NOT NULL,
    `history_json` json NOT NULL,
    `result_json` json,
    `revision` bigint NOT NULL DEFAULT 0,
    `expires_at` datetime(6) NOT NULL,
    `completed_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY `idx_text_game_sessions_expiry` (`expires_at`),
    KEY `idx_text_game_sessions_story_version` (`story_version_id`),
    CONSTRAINT `fk_text_game_sessions_story_version` FOREIGN KEY (`story_version_id`) REFERENCES `text_game_story_versions` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `text_game_session_events` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `session_id` char(36) NOT NULL,
    `sequence_no` int NOT NULL,
    `node_id` varchar(128) NOT NULL,
    `choice_id` varchar(128) NOT NULL,
    `effects_json` json NOT NULL,
    `state_after_json` json NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_text_game_session_event_sequence` (`session_id`, `sequence_no`),
    CONSTRAINT `fk_text_game_session_events_session` FOREIGN KEY (`session_id`) REFERENCES `text_game_sessions` (`session_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
