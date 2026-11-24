CREATE TABLE IF NOT EXISTS `okx_account_action_guard` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `account_scope` varchar(32) NOT NULL,
    UNIQUE KEY `uk_okx_account_action_scope` (`account_scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `okx_account_action_guard` (`account_scope`)
SELECT 'live' WHERE NOT EXISTS (SELECT 1 FROM `okx_account_action_guard` WHERE `account_scope` = 'live');
