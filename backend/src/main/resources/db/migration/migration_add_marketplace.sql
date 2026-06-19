CREATE TABLE IF NOT EXISTS `marketplace_users` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `username` varchar(64) NOT NULL UNIQUE,
    `password_hash` varchar(255) NOT NULL,
    `display_name` varchar(80) NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `marketplace_sessions` (
    `token_hash` char(64) NOT NULL PRIMARY KEY,
    `user_id` bigint NOT NULL,
    `expires_at` datetime(6) NOT NULL,
    `revoked_at` datetime(6),
    `last_seen_at` datetime(6) NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY `idx_marketplace_sessions_user` (`user_id`, `created_at`),
    KEY `idx_marketplace_sessions_expires_at` (`expires_at`),
    CONSTRAINT `fk_marketplace_sessions_user`
        FOREIGN KEY (`user_id`) REFERENCES `marketplace_users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `marketplace_categories` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `name` varchar(80) NOT NULL,
    `slug` varchar(80) NOT NULL UNIQUE,
    `sort_order` int NOT NULL DEFAULT 0,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '手机数码', 'phones-digital', 10
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'phones-digital');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '家用电器', 'home-appliances', 20
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'home-appliances');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '家具家居', 'furniture-home', 30
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'furniture-home');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '服饰鞋包', 'fashion-bags', 40
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'fashion-bags');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '图书文具', 'books-stationery', 50
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'books-stationery');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '运动户外', 'sports-outdoor', 60
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'sports-outdoor');
INSERT INTO `marketplace_categories` (`name`, `slug`, `sort_order`)
SELECT '其他', 'other', 70
WHERE NOT EXISTS (SELECT 1 FROM `marketplace_categories` WHERE `slug` = 'other');

CREATE TABLE IF NOT EXISTS `marketplace_items` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `seller_id` bigint NOT NULL,
    `category_id` bigint NOT NULL,
    `title` varchar(160) NOT NULL,
    `description` text NOT NULL,
    `image_url` varchar(1000) NOT NULL,
    `price` decimal(12,2),
    `status` varchar(16) NOT NULL DEFAULT 'LISTED',
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY `idx_marketplace_items_status_created` (`status`, `created_at`),
    KEY `idx_marketplace_items_category_status` (`category_id`, `status`, `created_at`),
    KEY `idx_marketplace_items_seller` (`seller_id`, `created_at`),
    CONSTRAINT `fk_marketplace_items_seller`
        FOREIGN KEY (`seller_id`) REFERENCES `marketplace_users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_marketplace_items_category`
        FOREIGN KEY (`category_id`) REFERENCES `marketplace_categories` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `marketplace_conversations` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `item_id` bigint NOT NULL,
    `buyer_id` bigint NOT NULL,
    `seller_id` bigint NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_marketplace_conversation_item_buyer` (`item_id`, `buyer_id`),
    KEY `idx_marketplace_conversations_buyer` (`buyer_id`, `updated_at`),
    KEY `idx_marketplace_conversations_seller` (`seller_id`, `updated_at`),
    CONSTRAINT `fk_marketplace_conversations_item`
        FOREIGN KEY (`item_id`) REFERENCES `marketplace_items` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_marketplace_conversations_buyer`
        FOREIGN KEY (`buyer_id`) REFERENCES `marketplace_users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_marketplace_conversations_seller`
        FOREIGN KEY (`seller_id`) REFERENCES `marketplace_users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `marketplace_messages` (
    `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `conversation_id` bigint NOT NULL,
    `sender_id` bigint NOT NULL,
    `body` text NOT NULL,
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY `idx_marketplace_messages_conversation` (`conversation_id`, `id`),
    CONSTRAINT `fk_marketplace_messages_conversation`
        FOREIGN KEY (`conversation_id`) REFERENCES `marketplace_conversations` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_marketplace_messages_sender`
        FOREIGN KEY (`sender_id`) REFERENCES `marketplace_users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
