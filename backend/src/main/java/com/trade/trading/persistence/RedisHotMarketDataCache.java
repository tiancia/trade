package com.trade.trading.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OrderBookLevel;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.market.HotMarketDataCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Redis implementation using expiring values and bounded candle sorted sets. */
@Component
public class RedisHotMarketDataCache implements HotMarketDataCache {
    private static final Logger log = LoggerFactory.getLogger(RedisHotMarketDataCache.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final StringRedisTemplate redisTemplate;
    private final TradingProperties.HotMarketDataCacheProperties config;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong retryAfterNanos = new AtomicLong();

    public RedisHotMarketDataCache(
            StringRedisTemplate redisTemplate,
            TradingProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.config = properties.getHotMarketDataCache();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public CacheWriteResult putSnapshot(String instId, TickerResp ticker, OrderBookResp orderBook) {
        if (!canAccessRedis() || ticker == null && orderBook == null) {
            return CacheWriteResult.SKIPPED;
        }

        try {
            Duration ttl = positiveDuration(config.getSnapshotTtlMs());
            if (ticker != null) {
                redisTemplate.opsForValue().set(
                        tickerKey(instId),
                        objectMapper.writeValueAsString(ticker),
                        ttl
                );
                recordOperation("write", "ticker", "cached");
            }
            if (orderBook != null) {
                redisTemplate.opsForValue().set(
                        orderBookKey(instId),
                        objectMapper.writeValueAsString(RedisOrderBook.from(orderBook)),
                        ttl
                );
                recordOperation("write", "order_book", "cached");
            }
            markAvailable();
            return CacheWriteResult.CACHED;
        } catch (Exception e) {
            recordFailure("write_snapshot", e);
            return CacheWriteResult.FAILED;
        }
    }

    @Override
    public CacheWriteResult putCandles(String instId, String bar, List<CandleResp> candles) {
        if (!canAccessRedis() || candles == null || candles.isEmpty()) {
            return CacheWriteResult.SKIPPED;
        }

        String key = candleKey(instId, bar);
        int retained = Math.max(config.getCandleLimit(), 1);
        int cached = 0;
        try {
            ZSetOperations<String, String> values = redisTemplate.opsForZSet();
            for (CandleResp candle : candles) {
                Long timestamp = timestamp(candle);
                if (timestamp == null) {
                    continue;
                }
                String json = objectMapper.writeValueAsString(toArray(candle));
                double score = timestamp.doubleValue();
                values.removeRangeByScore(key, score, score);
                values.add(key, json, score);
                cached++;
            }
            if (cached == 0) {
                return CacheWriteResult.SKIPPED;
            }

            Long size = values.size(key);
            if (size != null && size > retained) {
                values.removeRange(key, 0, size - retained - 1);
            }
            redisTemplate.expire(key, positiveDuration(config.getCandleTtlMs()));
            recordOperation("write", "candles", "cached");
            markAvailable();
            return CacheWriteResult.CACHED;
        } catch (Exception e) {
            recordFailure("write_candles", e);
            return CacheWriteResult.FAILED;
        }
    }

    @Override
    public Optional<TickerResp> latestTicker(String instId) {
        return readValue(tickerKey(instId), TickerResp.class, "ticker");
    }

    @Override
    public Optional<OrderBookResp> latestOrderBook(String instId) {
        return readValue(orderBookKey(instId), RedisOrderBook.class, "order_book")
                .map(RedisOrderBook::toResponse);
    }

    @Override
    public List<CandleResp> recentCandles(String instId, String bar, int limit) {
        if (!canAccessRedis() || limit <= 0) {
            return List.of();
        }

        try {
            Set<String> values = redisTemplate.opsForZSet()
                    .reverseRange(candleKey(instId, bar), 0, limit - 1L);
            if (values == null || values.isEmpty()) {
                recordOperation("read", "candles", "miss");
                markAvailable();
                return List.of();
            }

            List<CandleResp> candles = new ArrayList<>(values.size());
            for (String value : values) {
                try {
                    candles.add(new CandleResp(objectMapper.readValue(value, STRING_LIST)));
                } catch (JsonProcessingException e) {
                    recordFailure("decode_candle", e);
                }
            }
            recordOperation("read", "candles", candles.isEmpty() ? "miss" : "hit");
            markAvailable();
            return List.copyOf(candles);
        } catch (Exception e) {
            recordFailure("read_candles", e);
            return List.of();
        }
    }

    private <T> Optional<T> readValue(String key, Class<T> type, String kind) {
        if (!canAccessRedis()) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                recordOperation("read", kind, "miss");
                markAvailable();
                return Optional.empty();
            }
            T decoded = objectMapper.readValue(value, type);
            recordOperation("read", kind, "hit");
            markAvailable();
            return Optional.of(decoded);
        } catch (Exception e) {
            recordFailure("read_" + kind, e);
            return Optional.empty();
        }
    }

    private boolean canAccessRedis() {
        if (config == null || !config.isEnabled()) {
            return false;
        }
        if (System.nanoTime() < retryAfterNanos.get()) {
            recordOperation("access", "redis", "retry_deferred");
            return false;
        }
        return true;
    }

    private void markAvailable() {
        retryAfterNanos.set(0L);
    }

    private void recordFailure(String operation, Exception error) {
        long failures = failureCount.incrementAndGet();
        long retryNanos = Duration.ofMillis(Math.max(config.getFailureRetryIntervalMs(), 1L)).toNanos();
        retryAfterNanos.set(System.nanoTime() + retryNanos);
        recordOperation(operation, "redis", "failed");
        if (failures == 1 || failures % 100 == 0) {
            log.warn("Redis hot market-data cache failed: operation={}, failures={}, retryInMs={}",
                    operation, failures, config.getFailureRetryIntervalMs(), error);
        }
    }

    private void recordOperation(String operation, String kind, String outcome) {
        meterRegistry.counter(
                "trade.trading.hot.market.cache.operations",
                "operation", operation,
                "kind", kind,
                "outcome", outcome
        ).increment();
    }

    private String tickerKey(String instId) {
        return key("ticker", instId);
    }

    private String orderBookKey(String instId) {
        return key("order-book", instId);
    }

    private String candleKey(String instId, String bar) {
        return key("candles", instId, normalized(bar));
    }

    private String key(String... parts) {
        String prefix = config.getKeyPrefix();
        prefix = prefix == null || prefix.isBlank() ? "trade:trading:hot-market" : prefix.trim();
        while (prefix.endsWith(":")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + ":" + String.join(":", parts);
    }

    private static String normalized(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Duration positiveDuration(long millis) {
        return Duration.ofMillis(Math.max(millis, 1L));
    }

    private static Long timestamp(CandleResp candle) {
        if (candle == null || candle.getTs() == null || candle.getTs().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(candle.getTs());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> toArray(CandleResp candle) {
        return List.of(
                text(candle.getTs()),
                text(candle.getOpen()),
                text(candle.getHigh()),
                text(candle.getLow()),
                text(candle.getClose()),
                text(candle.getVol()),
                text(candle.getVolCcy()),
                text(candle.getVolCcyQuote()),
                text(candle.getConfirm())
        );
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private record RedisOrderBook(
            List<List<String>> asks,
            List<List<String>> bids,
            String ts,
            Long seqId
    ) {
        private static RedisOrderBook from(OrderBookResp response) {
            return new RedisOrderBook(
                    encodeLevels(response.getAsks()),
                    encodeLevels(response.getBids()),
                    response.getTs(),
                    response.getSeqId()
            );
        }

        private OrderBookResp toResponse() {
            OrderBookResp response = new OrderBookResp();
            response.setAsks(decodeLevels(asks));
            response.setBids(decodeLevels(bids));
            response.setTs(ts);
            response.setSeqId(seqId);
            return response;
        }

        private static List<List<String>> encodeLevels(List<OrderBookLevel> levels) {
            if (levels == null || levels.isEmpty()) {
                return List.of();
            }
            return levels.stream()
                    .map(level -> List.of(
                            text(level.getPx()),
                            text(level.getSz()),
                            text(level.getLiquidatedOrders()),
                            text(level.getOrders())
                    ))
                    .toList();
        }

        private static List<OrderBookLevel> decodeLevels(List<List<String>> levels) {
            if (levels == null || levels.isEmpty()) {
                return List.of();
            }
            return levels.stream().map(OrderBookLevel::new).toList();
        }
    }
}
