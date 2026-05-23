package com.trade.automation.application;

import com.trade.automation.config.AutomationProperties;
import com.trade.automation.model.AutomationLoopDefinition;
import com.trade.automation.model.AutomationTaskDefinition;
import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.scheduler.AiPolymarketScheduler;
import com.trade.story.config.AiStoryProperties;
import com.trade.story.scheduler.AiStoryScheduler;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.market.OkxMarketDataWebSocketFeed;
import com.trade.trading.scheduler.AiTradingScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class AutomationTaskRegistrar {

    public AutomationTaskRegistrar(
            AutomationTaskManager manager,
            AutomationProperties automationProperties,
            AiTradingScheduler tradingScheduler,
            TradingProperties tradingProperties,
            OkxMarketDataWebSocketFeed marketDataWebSocketFeed,
            AiPolymarketScheduler polymarketScheduler,
            AiPolymarketProperties polymarketProperties,
            AiStoryScheduler storyScheduler,
            AiStoryProperties storyProperties
    ) {
        manager.register(new AutomationTaskDefinition(
                "trading",
                "OKX AI trading",
                automationProperties.getTrading().isAutoStart(),
                marketDataWebSocketFeed::start,
                marketDataWebSocketFeed::stop,
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
