package com.trade.trading.scheduler;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.application.TradingStrategyEngine;
import com.trade.trading.application.OrderReconciliationService;
import com.trade.trading.application.TradingLeadershipService;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.market.HotMarketDataCache;
import com.trade.trading.market.MarketContextCollector;
import com.trade.trading.market.OkxMarketDataWebSocketFeed;
import com.trade.trading.market.TradingEventDetector;
import com.trade.trading.model.MarketSignal;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.persistence.TradingStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin trigger layer for OKX strategy trading.
 *
 * <p>Scheduled decisions always call the strategy engine. Event scans first
 * look for market movement, volume, or local-risk events, then run the same
 * engine with cooldown protection.</p>
 */
@Component
public class TradingScheduler {
    private static final Logger log = LoggerFactory.getLogger(TradingScheduler.class);

    private final TradingStrategyEngine tradingEngine;
    private final OrderReconciliationService orderReconciliationService;
    private final TradingLeadershipService leadershipService;
    private final MarketContextCollector marketContextCollector;
    private final OkxMarketDataWebSocketFeed marketDataWebSocketFeed;
    private final HotMarketDataCache hotMarketDataCache;
    private final TradingEventDetector eventDetector;
    private final TradingStateRepository stateRepository;
    private final TradingProperties properties;
    private Instant lastEventDecisionAt = Instant.EPOCH;

    public TradingScheduler(
            TradingStrategyEngine tradingEngine,
            OrderReconciliationService orderReconciliationService,
            TradingLeadershipService leadershipService,
            MarketContextCollector marketContextCollector,
            OkxMarketDataWebSocketFeed marketDataWebSocketFeed,
            HotMarketDataCache hotMarketDataCache,
            TradingEventDetector eventDetector,
            TradingStateRepository stateRepository,
            TradingProperties properties
    ) {
        this.tradingEngine = tradingEngine;
        this.orderReconciliationService = orderReconciliationService;
        this.leadershipService = leadershipService;
        this.marketContextCollector = marketContextCollector;
        this.marketDataWebSocketFeed = marketDataWebSocketFeed;
        this.hotMarketDataCache = hotMarketDataCache;
        this.eventDetector = eventDetector;
        this.stateRepository = stateRepository;
        this.properties = properties;
    }

    public void runScheduledDecision() {
        leadershipService.runIfLeader(
                "scheduled-decision",
                () -> tradingEngine.runDecision(TradingTrigger.scheduled())
        );
    }

    public void reconcileOrders() {
        leadershipService.runIfLeader(
                "order-reconciliation",
                orderReconciliationService::reconcileOnce
        );
    }

    public void scanEventTriggers() {
        leadershipService.runIfLeader("event-scan", this::scanEventTriggersAsLeader);
    }

    private void scanEventTriggersAsLeader() {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            List<CandleResp> candles = eventCandles();
            TickerResp ticker = eventTicker();
            List<MarketSignal> events = eventDetector.detect(ticker, candles, stateRepository.getState());
            if (events.isEmpty()) {
                return;
            }

            if (Duration.between(lastEventDecisionAt, Instant.now()).toMillis() < properties.getEventCooldownMs()) {
                log.info("Strategy event trigger detected but still in cooldown: events={}", events);
                return;
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("events", events);
            details.put("eventCount", events.size());
            details.put("cooldownMs", properties.getEventCooldownMs());
            boolean ran = tradingEngine.runDecision(TradingTrigger.event("Market event trigger", details));
            if (ran) {
                lastEventDecisionAt = Instant.now();
            }
        } catch (Exception e) {
            log.error("Strategy trading event scan failed", e);
        }
    }

    private List<CandleResp> eventCandles() {
        List<CandleResp> candles = marketDataWebSocketFeed.recentOneMinuteCandles(properties.getOneMinuteCandleLimit());
        if (hasEnoughCandlesForEventDetection(candles)) {
            return candles;
        }
        candles = hotMarketDataCache.recentCandles(
                properties.getInstId(),
                "1m",
                properties.getOneMinuteCandleLimit()
        );
        if (hasEnoughCandlesForEventDetection(candles)) {
            return candles;
        }
        return marketContextCollector.getOneMinuteCandles();
    }

    private TickerResp eventTicker() {
        return marketDataWebSocketFeed.latestTicker()
                .or(() -> hotMarketDataCache.latestTicker(properties.getInstId()))
                .orElseGet(marketContextCollector::getTicker);
    }

    private static boolean hasEnoughCandlesForEventDetection(List<CandleResp> candles) {
        return candles != null && candles.stream()
                .filter(candle -> "1".equals(candle.getConfirm()))
                .count() >= 21;
    }
}
