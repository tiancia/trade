package com.trade.trading.application.runtime;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.application.market.TradingEventDetector;
import com.trade.trading.application.market.CandleFreshness;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.application.order.OrderReconciliationService;
import com.trade.trading.port.HotMarketDataCache;
import com.trade.trading.application.strategy.TradingStrategyEngine;
import com.trade.trading.domain.model.MarketSignal;
import com.trade.trading.domain.model.TradingTrigger;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.port.TradingMarketSource;
import com.trade.trading.port.TradingMarketFeed;
import com.trade.trading.port.TradingStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Coordinates scheduled decisions, reconciliation, and market-event scans.
 *
 * <p>Scheduled decisions always call the strategy engine. Event scans first
 * look for market movement, volume, or local-risk events, then run the same
 * engine with cooldown protection.</p>
 */
@Component
public class TradingTriggerService {
    private static final Logger log = LoggerFactory.getLogger(TradingTriggerService.class);

    private final TradingStrategyEngine tradingEngine;
    private final OrderReconciliationService orderReconciliationService;
    private final TradingLeadershipService leadershipService;
    private final TradingMarketSource marketContextCollector;
    private final TradingMarketFeed marketDataWebSocketFeed;
    private final HotMarketDataCache hotMarketDataCache;
    private final TradingEventDetector eventDetector;
    private final TradingStateStore stateRepository;
    private final TradingProperties properties;
    private final PaperAccountService paperAccountService;
    private final Clock clock;
    private Instant lastEventDecisionAt = Instant.EPOCH;

    @Autowired
    public TradingTriggerService(
            TradingStrategyEngine tradingEngine,
            OrderReconciliationService orderReconciliationService,
            TradingLeadershipService leadershipService,
            TradingMarketSource marketContextCollector,
            TradingMarketFeed marketDataWebSocketFeed,
            HotMarketDataCache hotMarketDataCache,
            TradingEventDetector eventDetector,
            TradingStateStore stateRepository,
            TradingProperties properties,
            PaperAccountService paperAccountService
    ) {
        this(tradingEngine, orderReconciliationService, leadershipService, marketContextCollector,
                marketDataWebSocketFeed, hotMarketDataCache, eventDetector, stateRepository,
                properties, paperAccountService, Clock.systemUTC());
    }

    TradingTriggerService(TradingStrategyEngine tradingEngine, OrderReconciliationService orderReconciliationService,
                          TradingLeadershipService leadershipService, TradingMarketSource marketContextCollector,
                          TradingMarketFeed marketDataWebSocketFeed, HotMarketDataCache hotMarketDataCache,
                          TradingEventDetector eventDetector, TradingStateStore stateRepository,
                          TradingProperties properties, PaperAccountService paperAccountService, Clock clock) {
        this.tradingEngine = tradingEngine;
        this.orderReconciliationService = orderReconciliationService;
        this.leadershipService = leadershipService;
        this.marketContextCollector = marketContextCollector;
        this.marketDataWebSocketFeed = marketDataWebSocketFeed;
        this.hotMarketDataCache = hotMarketDataCache;
        this.eventDetector = eventDetector;
        this.stateRepository = stateRepository;
        this.properties = properties;
        this.paperAccountService = paperAccountService;
        this.clock = clock;
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
                () -> orderReconciliationService.reconcileOnce()
        );
    }

    public void scanEventTriggers() {
        leadershipService.runIfLeader("event-scan", () -> scanEventTriggersAsLeader());
    }

    private void scanEventTriggersAsLeader() {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            if (tradingEngine.isIntradayStrategyActive()) {
                // Closed 15m strategies provide their own setup and volatility gates.
                // Persistent signal/order identities handle repeated observations.
                tradingEngine.runDecision(TradingTrigger.event("Closed 15m strategy scan", Map.of("signalBar", "15m")));
                return;
            }
            List<CandleResp> candles = eventCandles();
            TickerResp ticker = eventTicker();
            TradingState state = stateRepository.getState();
            if (properties.getExecutionMode() == ExecutionMode.PAPER) {
                TradingDecisionContext context = new TradingDecisionContext().setTicker(ticker).setTradingState(state);
                paperAccountService.enrichContext(context);
                state = context.getTradingState();
            }
            List<MarketSignal> events = eventDetector.detect(ticker, candles, state);
            if (events.isEmpty()) {
                return;
            }

            if (Duration.between(lastEventDecisionAt, clock.instant()).toMillis() < properties.getEventCooldownMs()) {
                log.info("Strategy event trigger detected but still in cooldown: events={}", events);
                return;
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("events", events);
            details.put("eventCount", events.size());
            details.put("cooldownMs", properties.getEventCooldownMs());
            boolean ran = tradingEngine.runDecision(TradingTrigger.event("Market event trigger", details));
            if (ran) {
                lastEventDecisionAt = clock.instant();
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

    private boolean hasEnoughCandlesForEventDetection(List<CandleResp> candles) {
        return CandleFreshness.isCurrent(candles, "1m", clock.instant()) && candles.stream()
                .filter(Objects::nonNull)
                .filter(candle -> "1".equals(candle.getConfirm()))
                .count() >= 21;
    }
}
