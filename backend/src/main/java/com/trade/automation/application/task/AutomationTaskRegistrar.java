package com.trade.automation.application.task;

import com.trade.automation.domain.model.AutomationLoopDefinition;
import com.trade.automation.domain.model.AutomationTaskDefinition;
import com.trade.automation.infrastructure.config.AutomationProperties;
import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import com.trade.polymarket.interfaces.scheduler.AiPolymarketScheduler;
import com.trade.story.infrastructure.config.AiStoryProperties;
import com.trade.story.interfaces.scheduler.AiStoryScheduler;
import com.trade.trading.application.runtime.TradingMarketDataRuntime;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.interfaces.scheduler.TradingScheduler;
import com.trade.weibo.interfaces.scheduler.WeiboScheduler;
import com.trade.weibo.infrastructure.config.WeiboWorkflowProperties;
import com.trade.weibo.infrastructure.config.WeiboTelegramReviewProperties;
import com.trade.x.interfaces.scheduler.XScheduler;
import com.trade.x.infrastructure.config.XWorkflowProperties;
import com.trade.x.infrastructure.config.XTelegramReviewProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Registers domain-owned background loops with the shared automation manager.
 *
 * <p>This class is the quickest map from {@code trade.automation.*} startup
 * switches to the concrete loops that run OKX trading, Polymarket decisions,
 * story generation, and reviewed Weibo/X publishing.</p>
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
            AiStoryProperties storyProperties,
            WeiboScheduler weiboScheduler,
            WeiboWorkflowProperties weiboProperties,
            WeiboTelegramReviewProperties weiboReviewProperties,
            XScheduler xScheduler,
            XWorkflowProperties xProperties,
            XTelegramReviewProperties xReviewProperties
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

        manager.register(new AutomationTaskDefinition(
                "weibo", "Reviewed AI Weibo publishing", automationProperties.getWeibo().isAutoStart(),
                null, null, List.of(
                    new AutomationLoopDefinition("generation", millis(weiboProperties.getInitialDelayMs()),
                            millis(weiboProperties.getGenerationFixedDelayMs()), weiboScheduler::generate),
                    new AutomationLoopDefinition("review", millis(weiboProperties.getInitialDelayMs()),
                            millis(weiboReviewProperties.getPollingFixedDelayMs()), weiboScheduler::review),
                    new AutomationLoopDefinition("publishing", millis(weiboProperties.getInitialDelayMs()),
                            millis(weiboProperties.getPublishingFixedDelayMs()), weiboScheduler::publish)
                )
        ));

        manager.register(new AutomationTaskDefinition(
                "x", "Reviewed AI X publishing", automationProperties.getX().isAutoStart(),
                null, null, List.of(
                    new AutomationLoopDefinition("generation", millis(xProperties.getInitialDelayMs()),
                            millis(xProperties.getGenerationFixedDelayMs()), xScheduler::generate),
                    new AutomationLoopDefinition("review", millis(xProperties.getInitialDelayMs()),
                            millis(xReviewProperties.getPollingFixedDelayMs()), xScheduler::review),
                    new AutomationLoopDefinition("publishing", millis(xProperties.getInitialDelayMs()),
                            millis(xProperties.getPublishingFixedDelayMs()), xScheduler::publish)
                )
        ));
    }

    private static Duration millis(long value) {
        return Duration.ofMillis(Math.max(value, 0L));
    }
}
