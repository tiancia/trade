package com.trade.polymarket.scheduler;

import com.trade.polymarket.application.AiPolymarketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scheduler-facing adapter for one Polymarket AI decision run.
 *
 * <p>The automation manager owns timing; this class only logs the trigger and
 * delegates the actual collect-prompt-parse-execute flow to the service.</p>
 */
@Component
public class AiPolymarketScheduler {
    private static final Logger log = LoggerFactory.getLogger(AiPolymarketScheduler.class);

    private final AiPolymarketService service;

    public AiPolymarketScheduler(AiPolymarketService service) {
        this.service = service;
    }

    public void runScheduledDecision() {
        long startedAt = System.currentTimeMillis();
        log.info("AI Polymarket scheduled trigger fired");
        boolean ran = service.runDecision();
        log.info(
                "AI Polymarket scheduled trigger finished: ran={}, elapsedMs={}",
                ran,
                System.currentTimeMillis() - startedAt
        );
    }
}
