-- Run this migration manually only when the application schema initializer is disabled.
-- The same definitions are also included in ai_trade_mysql_schema.sql.

CREATE TABLE IF NOT EXISTS `okx_market_snapshots` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Surrogate primary key',
    `inst_id` varchar(64) NOT NULL COMMENT 'OKX instrument identifier',
    `source` varchar(64) NOT NULL COMMENT 'REST or WebSocket collection path',
    `market_ts` bigint DEFAULT NULL COMMENT 'Ticker exchange timestamp in epoch milliseconds',
    `last_price` decimal(38,18) DEFAULT NULL COMMENT 'Last traded price',
    `last_size` decimal(38,18) DEFAULT NULL COMMENT 'Last traded size',
    `bid_price` decimal(38,18) DEFAULT NULL COMMENT 'Best bid price',
    `bid_size` decimal(38,18) DEFAULT NULL COMMENT 'Best bid size',
    `ask_price` decimal(38,18) DEFAULT NULL COMMENT 'Best ask price',
    `ask_size` decimal(38,18) DEFAULT NULL COMMENT 'Best ask size',
    `open_24h` decimal(38,18) DEFAULT NULL COMMENT 'Rolling 24-hour opening price',
    `high_24h` decimal(38,18) DEFAULT NULL COMMENT 'Rolling 24-hour high price',
    `low_24h` decimal(38,18) DEFAULT NULL COMMENT 'Rolling 24-hour low price',
    `vol_ccy_24h` decimal(38,18) DEFAULT NULL COMMENT 'Rolling 24-hour currency volume',
    `vol_24h` decimal(38,18) DEFAULT NULL COMMENT 'Rolling 24-hour base or contract volume',
    `order_book_ts` bigint DEFAULT NULL COMMENT 'Order-book exchange timestamp in epoch milliseconds',
    `sequence_id` bigint DEFAULT NULL COMMENT 'OKX order-book sequence identifier',
    `ticker_json` json DEFAULT NULL COMMENT 'Original ticker payload',
    `order_book_json` json DEFAULT NULL COMMENT 'Original order-book payload',
    `collected_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Database collection time',
    PRIMARY KEY (`id`),
    KEY `idx_okx_market_snapshots_inst_collected` (`inst_id`, `collected_at`),
    KEY `idx_okx_market_snapshots_source_collected` (`source`, `collected_at`),
    KEY `idx_okx_market_snapshots_market_ts` (`inst_id`, `market_ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Ticker and order-book snapshots collected by the trading module';

CREATE TABLE IF NOT EXISTS `okx_candle_cache` (
    `inst_id` varchar(64) NOT NULL COMMENT 'OKX instrument identifier',
    `bar` varchar(32) NOT NULL COMMENT 'Candlestick interval such as 1m or 5m',
    `ts` bigint NOT NULL COMMENT 'Candle opening timestamp in epoch milliseconds',
    `open` decimal(38,18) DEFAULT NULL COMMENT 'Opening price',
    `high` decimal(38,18) DEFAULT NULL COMMENT 'Highest price',
    `low` decimal(38,18) DEFAULT NULL COMMENT 'Lowest price',
    `close` decimal(38,18) DEFAULT NULL COMMENT 'Closing price',
    `vol` decimal(38,18) DEFAULT NULL COMMENT 'Trading volume',
    `vol_ccy` decimal(38,18) DEFAULT NULL COMMENT 'Trading volume in currency',
    `vol_ccy_quote` decimal(38,18) DEFAULT NULL COMMENT 'Trading volume in quote currency',
    `confirm` varchar(8) DEFAULT NULL COMMENT 'OKX candle completion flag',
    `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'Last database update time',
    PRIMARY KEY (`inst_id`, `bar`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Latest persisted form of each OKX candle';
