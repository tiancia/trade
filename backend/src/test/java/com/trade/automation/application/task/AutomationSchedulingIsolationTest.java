package com.trade.automation.application.task;

import com.trade.automation.domain.model.AutomationLoopDefinition;
import com.trade.automation.domain.model.AutomationTaskDefinition;
import com.trade.automation.infrastructure.config.AutomationConfiguration;
import com.trade.trading.application.port.TradingLeaderLeaseRepository;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.domain.model.TradingLeaderLease;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.weibo.infrastructure.config.WeiboTelegramReviewProperties;
import com.trade.x.infrastructure.config.XTelegramReviewProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutomationSchedulingIsolationTest {
    @Test
    void blockedContentWorkCannotStarveTradingLoopsOrLeaseHeartbeat() throws InterruptedException {
        CountDownLatch allContentEntered = new CountDownLatch(4);
        CountDownLatch releaseContent = new CountDownLatch(1);
        CountDownLatch tradingRan = new CountDownLatch(1);
        CountDownLatch heartbeatRan = new CountDownLatch(1);
        AtomicInteger acquisitions = new AtomicInteger();
        TradingLeaderLeaseRepository leases = mock(TradingLeaderLeaseRepository.class);
        when(leases.acquireOrRenew(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            if (acquisitions.incrementAndGet() > 1) heartbeatRan.countDown();
            Instant now = Instant.now();
            return new TradingLeaderLease(invocation.getArgument(0), invocation.getArgument(1),
                    now.plusSeconds(30), 1, now);
        });
        TradingProperties properties = new TradingProperties();
        properties.getLeadership().setEnabled(true);
        properties.getLeadership().setHeartbeatIntervalMs(25);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AutomationConfiguration.class, AutomationTaskManager.class, TradingLeadershipService.class);
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(TradingProperties.class, () -> properties);
            context.registerBean(TradingLeaderLeaseRepository.class, () -> leases);
            context.registerBean(WeiboTelegramReviewProperties.class, WeiboTelegramReviewProperties::new);
            context.registerBean(XTelegramReviewProperties.class, XTelegramReviewProperties::new);
            context.refresh();
            AutomationTaskManager manager = context.getBean(AutomationTaskManager.class);
            TradingLeadershipService leadership = context.getBean(TradingLeadershipService.class);
            try {
                for (String task : List.of("story", "polymarket", "weibo", "x")) {
                    manager.register(task(task, () -> {
                        allContentEntered.countDown();
                        try {
                            if (!releaseContent.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Blocked test task was not released");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    }));
                    manager.start(task);
                }
                assertTrue(allContentEntered.await(5, TimeUnit.SECONDS), "all four shared threads must be occupied");

                manager.register(task("trading", tradingRan::countDown));
                manager.start("trading");
                leadership.start();

                assertTrue(tradingRan.await(5, TimeUnit.SECONDS), "trading must run while content work is blocked");
                assertTrue(heartbeatRan.await(5, TimeUnit.SECONDS), "the lease heartbeat must retain scheduling capacity");
                assertEquals(1L, releaseContent.getCount(), "content work must remain blocked until both checks finish");
            } finally {
                releaseContent.countDown();
                leadership.stop();
                manager.destroy();
            }
        }
    }

    private static AutomationTaskDefinition task(String id, Runnable action) {
        return new AutomationTaskDefinition(id, id, false, null, null,
                List.of(new AutomationLoopDefinition("loop", Duration.ZERO, Duration.ofMinutes(1), action)));
    }
}
