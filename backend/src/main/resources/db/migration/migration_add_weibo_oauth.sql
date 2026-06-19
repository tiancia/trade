CREATE TABLE IF NOT EXISTS `weibo_oauth_state` (
    `state` varchar(128) NOT NULL PRIMARY KEY,
    `expires_at` datetime(6) NOT NULL,
    `used_at` datetime(6),
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY `idx_weibo_oauth_state_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `weibo_account_token` (
    `uid` varchar(64) NOT NULL PRIMARY KEY,
    `access_token` text NOT NULL,
    `expires_at` datetime(6) NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY `idx_weibo_account_token_updated_at` (`updated_at`),
    KEY `idx_weibo_account_token_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
