package com.trade.polymarket.scheduler;

import com.trade.polymarket.application.AiPolymarketService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiPolymarketScheduler {
    private final AiPolymarketService service;

    public AiPolymarketScheduler(AiPolymarketService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${trade.polymarket.decision-fixed-delay-ms:1800000}",
            initialDelayString = "${trade.polymarket.initial-delay-ms:60000}"
    )
    public void runScheduledDecision() {
        service.runDecision();
    }
}
