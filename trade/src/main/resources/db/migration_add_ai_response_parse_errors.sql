CREATE TABLE IF NOT EXISTS `ai_response_parse_errors` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `source` varchar(64) NOT NULL,
    `phase` varchar(64),
    `related_id` varchar(128),
    `prompt_text` mediumtext,
    `raw_response` mediumtext,
    `error_message` text NOT NULL,
    `fallback_action` varchar(64),
    `metadata_json` json,
    KEY `idx_ai_response_parse_errors_created_at` (`created_at`),
    KEY `idx_ai_response_parse_errors_source_phase` (`source`, `phase`, `created_at`),
    KEY `idx_ai_response_parse_errors_related_id` (`related_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
