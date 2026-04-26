package com.trade.trading.scheduler;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.AiTradingProperties;
import com.trade.trading.model.TradingEvent;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.service.AiTradingService;
import com.trade.trading.service.MarketContextCollector;
import com.trade.trading.service.TradingEventDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiTradingScheduler {
    private static final Logger log = LoggerFactory.getLogger(AiTradingScheduler.class);

    private final AiTradingService tradingService;
    private final MarketContextCollector marketContextCollector;
    private final TradingEventDetector eventDetector;
    private final TradingStateRepository stateRepository;
    private final AiTradingProperties properties;
    private Instant lastEventDecisionAt = Instant.EPOCH;

    public AiTradingScheduler(
            AiTradingService tradingService,
            MarketContextCollector marketContextCollector,
            TradingEventDetector eventDetector,
            TradingStateRepository stateRepository,
            AiTradingProperties properties
    ) {
        this.tradingService = tradingService;
        this.marketContextCollector = marketContextCollector;
        this.eventDetector = eventDetector;
        this.stateRepository = stateRepository;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${trade.ai.decision-fixed-delay-ms:1800000}",
            initialDelayString = "${trade.ai.initial-delay-ms:30000}"
    )
    public void runScheduledDecision() {
        tradingService.runDecision(TradingTrigger.scheduled());
    }

    @Scheduled(
            fixedDelayString = "${trade.ai.event-scan-fixed-delay-ms:60000}",
            initialDelayString = "${trade.ai.event-initial-delay-ms:30000}"
    )
    public void scanEventTriggers() {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            List<CandleResp> candles = marketContextCollector.getOneMinuteCandles();
            TickerResp ticker = marketContextCollector.getTicker();
            List<TradingEvent> events = eventDetector.detect(ticker, candles, stateRepository.getState());
            if (events.isEmpty()) {
                return;
            }

            if (Duration.between(lastEventDecisionAt, Instant.now()).toMillis() < properties.getEventCooldownMs()) {
                log.info("AI event trigger detected but still in cooldown: events={}", events);
                return;
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("events", events);
            details.put("eventCount", events.size());
            details.put("cooldownMs", properties.getEventCooldownMs());
            boolean ran = tradingService.runDecision(TradingTrigger.event("Market event trigger", details));
            if (ran) {
                lastEventDecisionAt = Instant.now();
            }
        } catch (Exception e) {
            log.error("AI trading event scan failed", e);
        }
    }
}
