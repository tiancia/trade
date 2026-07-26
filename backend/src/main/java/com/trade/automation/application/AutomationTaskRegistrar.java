package com.trade.automation.application;

import com.trade.automation.config.AutomationProperties;
import com.trade.automation.model.AutomationLoopDefinition;
import com.trade.automation.model.AutomationTaskDefinition;
import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.scheduler.AiPolymarketScheduler;
import com.trade.story.config.AiStoryProperties;
import com.trade.story.scheduler.AiStoryScheduler;
import com.trade.trading.application.TradingMarketDataRuntime;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.scheduler.TradingScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Registers domain-owned background loops with the shared automation manager.
 *
 * <p>This class is the quickest map from {@code trade.automation.*} startup
 * switches to the concrete loops that run OKX trading, Polymarket decisions,
 * and story generation.</p>
 */
@Component
public class AutomationTaskRegistrar {

    public AutomationTaskRegistrar(
            AutomationTaskManager manager,
            AutomationProperties automationProperties,
            TradingScheduler tradingScheduler,
            TradingProperties tradingProperties,
            TradingMarketDataRuntime tradingMarketDataRuntime,
            AiPolymarketScheduler polymarketScheduler,
            AiPolymarketProperties polymarketProperties,
            AiStoryScheduler storyScheduler,
            AiStoryProperties storyProperties
    ) {
        manager.register(new AutomationTaskDefinition(
                "trading",
                "OKX strategy trading",
                automationProperties.getTrading().isAutoStart(),
                tradingMarketDataRuntime::start,
                tradingMarketDataRuntime::stop,
                List.of(
                        new AutomationLoopDefinition(
                                "decision",
                                millis(tradingProperties.getInitialDelayMs()),
                                millis(tradingProperties.getDecisionFixedDelayMs()),
                                tradingScheduler::runScheduledDecision
                        ),
                        new AutomationLoopDefinition(
                                "event-scan",
                                millis(tradingProperties.getEventInitialDelayMs()),
                                millis(tradingProperties.getEventScanFixedDelayMs()),
                                tradingScheduler::scanEventTriggers
                        ),
                        new AutomationLoopDefinition(
                                "reconciliation",
                                millis(tradingProperties.getReconciliation().getInitialDelayMs()),
                                millis(tradingProperties.getReconciliation().getFixedDelayMs()),
                                tradingScheduler::reconcileOrders
                        )
                )
        ));

        manager.register(new AutomationTaskDefinition(
                "polymarket",
                "Polymarket AI trading",
                automationProperties.getPolymarket().isAutoStart(),
                null,
                null,
                List.of(new AutomationLoopDefinition(
                        "decision",
                        millis(polymarketProperties.getInitialDelayMs()),
                        millis(polymarketProperties.getDecisionFixedDelayMs()),
                        polymarketScheduler::runScheduledDecision
                ))
        ));

        manager.register(new AutomationTaskDefinition(
                "story",
                "AI story generation",
                automationProperties.getStory().isAutoStart(),
                null,
                null,
                List.of(new AutomationLoopDefinition(
                        "generation",
                        millis(storyProperties.getInitialDelayMs()),
                        millis(storyProperties.getGenerationFixedDelayMs()),
                        storyScheduler::runScheduledGeneration
                ))
        ));
    }

    private static Duration millis(long value) {
        return Duration.ofMillis(Math.max(value, 0L));
    }
}
