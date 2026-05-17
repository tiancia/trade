-- One-time migration for databases created by the old UUID-based schema.
-- Run after taking a database backup. Do not run against a fresh database that
-- was already created from ai_trade_mysql_schema.sql.

SET FOREIGN_KEY_CHECKS = 0;

RENAME TABLE
    `decision_runs` TO `okx_decision_runs`,
    `ai_requests` TO `okx_ai_requests`,
    `ai_responses` TO `okx_ai_responses`,
    `order_executions` TO `okx_order_executions`;

ALTER TABLE `okx_ai_requests` DROP FOREIGN KEY `fk_ai_requests_decision`;
ALTER TABLE `okx_ai_responses` DROP FOREIGN KEY `fk_ai_responses_decision`;
ALTER TABLE `okx_order_executions` DROP FOREIGN KEY `fk_order_executions_decision`;

ALTER TABLE `okx_decision_runs`
    DROP PRIMARY KEY,
    CHANGE COLUMN `decision_id` `legacy_decision_uuid` char(36) NOT NULL,
    ADD COLUMN `id` bigint NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (`id`),
    ADD UNIQUE KEY `uk_okx_decision_runs_legacy_decision_uuid` (`legacy_decision_uuid`);

ALTER TABLE `okx_decision_runs`
    RENAME INDEX `idx_decision_runs_started_at` TO `idx_okx_decision_runs_started_at`;

ALTER TABLE `okx_decision_runs`
    RENAME INDEX `idx_decision_runs_inst_action` TO `idx_okx_decision_runs_inst_action`;

ALTER TABLE `okx_ai_requests`
    ADD COLUMN `decision_run_id` bigint AFTER `id`;

UPDATE `okx_ai_requests` request
JOIN `okx_decision_runs` run
    ON request.`decision_id` = run.`legacy_decision_uuid`
SET request.`decision_run_id` = run.`id`;

ALTER TABLE `okx_ai_requests`
    MODIFY COLUMN `decision_run_id` bigint NOT NULL,
    DROP COLUMN `decision_id`,
    ADD UNIQUE KEY `uk_okx_ai_requests_decision_run_id` (`decision_run_id`),
    ADD CONSTRAINT `fk_okx_ai_requests_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `okx_decision_runs` (`id`) ON DELETE CASCADE;

ALTER TABLE `okx_ai_responses`
    ADD COLUMN `decision_run_id` bigint AFTER `id`;

UPDATE `okx_ai_responses` response
JOIN `okx_decision_runs` run
    ON response.`decision_id` = run.`legacy_decision_uuid`
SET response.`decision_run_id` = run.`id`;

ALTER TABLE `okx_ai_responses`
    MODIFY COLUMN `decision_run_id` bigint NOT NULL,
    DROP COLUMN `decision_id`,
    ADD UNIQUE KEY `uk_okx_ai_responses_decision_run_id` (`decision_run_id`),
    ADD CONSTRAINT `fk_okx_ai_responses_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `okx_decision_runs` (`id`) ON DELETE CASCADE;

ALTER TABLE `okx_order_executions`
    RENAME INDEX `idx_order_executions_order_id` TO `idx_okx_order_executions_order_id`;

ALTER TABLE `okx_order_executions`
    ADD COLUMN `decision_run_id` bigint AFTER `id`;

UPDATE `okx_order_executions` execution
JOIN `okx_decision_runs` run
    ON execution.`decision_id` = run.`legacy_decision_uuid`
SET execution.`decision_run_id` = run.`id`;

ALTER TABLE `okx_order_executions`
    MODIFY COLUMN `decision_run_id` bigint NOT NULL,
    DROP COLUMN `decision_id`,
    ADD UNIQUE KEY `uk_okx_order_executions_decision_run_id` (`decision_run_id`),
    ADD CONSTRAINT `fk_okx_order_executions_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `okx_decision_runs` (`id`) ON DELETE CASCADE;

ALTER TABLE `okx_decision_runs`
    DROP INDEX `uk_okx_decision_runs_legacy_decision_uuid`,
    DROP COLUMN `legacy_decision_uuid`;

CREATE TABLE `polymarket_decision_runs` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `legacy_decision_uuid` char(36) NOT NULL,
    `started_at` datetime(6) NOT NULL,
    `completed_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `execution_enabled` tinyint(1) NOT NULL DEFAULT 0,
    `market_count` int,
    `outcome_count` int,
    `action` varchar(16),
    `decision_reason` text,
    `market_id` varchar(128),
    `market_slug` varchar(512),
    `market_question` text,
    `outcome` varchar(512),
    `token_id` varchar(128),
    `limit_price` decimal(38,18),
    `max_spend_usdc` decimal(38,18),
    `win_probability` decimal(38,18),
    `confidence` decimal(38,18),
    `estimated_probability` decimal(38,18),
    `estimated_edge` decimal(38,18),
    `execution_status` varchar(64),
    `skip_reason` text,
    `error` text,
    UNIQUE KEY `uk_polymarket_decision_runs_legacy_decision_uuid` (`legacy_decision_uuid`),
    KEY `idx_polymarket_decision_runs_started_at` (`started_at`),
    KEY `idx_polymarket_decision_runs_action` (`action`, `started_at`),
    KEY `idx_polymarket_decision_runs_token` (`token_id`, `started_at`),
    KEY `idx_polymarket_decision_runs_market_slug` (`market_slug`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `polymarket_ai_requests` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_run_id` bigint NOT NULL UNIQUE,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `prompt_text` mediumtext,
    `ai_parameters_json` json,
    CONSTRAINT `fk_polymarket_ai_requests_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `polymarket_decision_runs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `polymarket_ai_responses` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_run_id` bigint NOT NULL UNIQUE,
    `received_at` datetime(6) NOT NULL,
    `raw_response` mediumtext,
    `parsed_action` varchar(16),
    `parsed_reason` text,
    `parsed_market_id` varchar(128),
    `parsed_market_slug` varchar(512),
    `parsed_market_question` text,
    `parsed_outcome` varchar(512),
    `parsed_token_id` varchar(128),
    `parsed_limit_price` decimal(38,18),
    `parsed_max_spend_usdc` decimal(38,18),
    `parsed_win_probability` decimal(38,18),
    `parsed_confidence` decimal(38,18),
    `parsed_estimated_probability` decimal(38,18),
    `parsed_estimated_edge` decimal(38,18),
    CONSTRAINT `fk_polymarket_ai_responses_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `polymarket_decision_runs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `polymarket_order_executions` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `decision_run_id` bigint NOT NULL UNIQUE,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `execution_status` varchar(64),
    `skip_reason` text,
    `order_response` mediumtext,
    `error` text,
    CONSTRAINT `fk_polymarket_order_executions_decision_run`
        FOREIGN KEY (`decision_run_id`) REFERENCES `polymarket_decision_runs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `polymarket_decision_runs` (
    `legacy_decision_uuid`,
    `started_at`,
    `completed_at`,
    `created_at`,
    `execution_enabled`,
    `market_count`,
    `outcome_count`,
    `action`,
    `decision_reason`,
    `market_id`,
    `market_slug`,
    `market_question`,
    `outcome`,
    `token_id`,
    `limit_price`,
    `max_spend_usdc`,
    `win_probability`,
    `confidence`,
    `estimated_probability`,
    `estimated_edge`,
    `execution_status`,
    `skip_reason`,
    `error`
)
SELECT
    `decision_id`,
    `started_at`,
    `completed_at`,
    `created_at`,
    `execution_enabled`,
    `market_count`,
    `outcome_count`,
    `action`,
    `decision_reason`,
    `market_id`,
    `market_slug`,
    `market_question`,
    `outcome`,
    `token_id`,
    `limit_price`,
    `max_spend_usdc`,
    `win_probability`,
    `confidence`,
    `estimated_probability`,
    `estimated_edge`,
    `execution_status`,
    `skip_reason`,
    `error`
FROM `polymarket_decision_audits`;

INSERT INTO `polymarket_ai_requests` (
    `decision_run_id`,
    `created_at`,
    `prompt_text`,
    `ai_parameters_json`
)
SELECT
    run.`id`,
    audit.`created_at`,
    audit.`prompt_text`,
    audit.`ai_parameters_json`
FROM `polymarket_decision_audits` audit
JOIN `polymarket_decision_runs` run
    ON audit.`decision_id` = run.`legacy_decision_uuid`
WHERE audit.`prompt_text` IS NOT NULL
   OR audit.`ai_parameters_json` IS NOT NULL;

INSERT INTO `polymarket_ai_responses` (
    `decision_run_id`,
    `received_at`,
    `raw_response`,
    `parsed_action`,
    `parsed_reason`,
    `parsed_market_id`,
    `parsed_market_slug`,
    `parsed_market_question`,
    `parsed_outcome`,
    `parsed_token_id`,
    `parsed_limit_price`,
    `parsed_max_spend_usdc`,
    `parsed_win_probability`,
    `parsed_confidence`,
    `parsed_estimated_probability`,
    `parsed_estimated_edge`
)
SELECT
    run.`id`,
    COALESCE(audit.`completed_at`, audit.`created_at`, audit.`started_at`),
    audit.`raw_response`,
    audit.`action`,
    audit.`decision_reason`,
    audit.`market_id`,
    audit.`market_slug`,
    audit.`market_question`,
    audit.`outcome`,
    audit.`token_id`,
    audit.`limit_price`,
    audit.`max_spend_usdc`,
    audit.`win_probability`,
    audit.`confidence`,
    audit.`estimated_probability`,
    audit.`estimated_edge`
FROM `polymarket_decision_audits` audit
JOIN `polymarket_decision_runs` run
    ON audit.`decision_id` = run.`legacy_decision_uuid`
WHERE audit.`raw_response` IS NOT NULL
   OR audit.`action` IS NOT NULL;

INSERT INTO `polymarket_order_executions` (
    `decision_run_id`,
    `created_at`,
    `execution_status`,
    `skip_reason`,
    `order_response`,
    `error`
)
SELECT
    run.`id`,
    audit.`created_at`,
    audit.`execution_status`,
    audit.`skip_reason`,
    audit.`order_response`,
    audit.`error`
FROM `polymarket_decision_audits` audit
JOIN `polymarket_decision_runs` run
    ON audit.`decision_id` = run.`legacy_decision_uuid`
WHERE audit.`execution_status` IS NOT NULL
   OR audit.`skip_reason` IS NOT NULL
   OR audit.`order_response` IS NOT NULL
   OR audit.`error` IS NOT NULL;

ALTER TABLE `polymarket_decision_runs`
    DROP INDEX `uk_polymarket_decision_runs_legacy_decision_uuid`,
    DROP COLUMN `legacy_decision_uuid`;

DROP TABLE `polymarket_decision_audits`;

SET FOREIGN_KEY_CHECKS = 1;
