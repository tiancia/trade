package com.trade.trading.application.runtime;

import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.port.TradingLeaderLeaseRepository;
import com.trade.trading.port.TradingMarketFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradingMarketDataRuntimeTest {
    private final TradingMarketFeed feed = mock(TradingMarketFeed.class);
    private final TradingLeadershipService leadership = leadership();
    private final TradingMarketDataRuntime runtime = new TradingMarketDataRuntime(feed, leadership);

    @Test
    void stoppingBlocksLiveAdmissionBeforeWaitingForFeedCleanup() throws Exception {
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        doAnswer(ignored -> {
            cleanupStarted.countDown();
            assertTrue(allowCleanup.await(5, TimeUnit.SECONDS));
            return null;
        }).when(feed).stop();
        runtime.start();
        assertDoesNotThrow(leadership::requireLiveSubmissionReady);
        var workers = Executors.newSingleThreadExecutor();
        try {
            var stopping = workers.submit(() -> { runtime.stop(); });
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                    leadership::requireLiveSubmissionReady);
            assertFalse(runtime.isRunning());
            allowCleanup.countDown();
            stopping.get(5, TimeUnit.SECONDS);
        } finally {
            allowCleanup.countDown();
            workers.shutdownNow();
            leadership.stop();
        }
    }

    @Test
    void failedFeedCleanupCannotLeaveTradingAdmittedAndStillInvokesLifecycleCallback() {
        RuntimeException failure = new IllegalStateException("feed cleanup failed");
        doThrow(failure).when(feed).stop();
        runtime.start();
        Runnable callback = mock(Runnable.class);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> runtime.stop(callback)));

        verify(callback).run();
        assertFalse(runtime.isRunning());
        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                leadership::requireLiveSubmissionReady);
    }

    @Test
    void partialFeedStartupIsCleanedUpAndCanBeRestarted() {
        RuntimeException failure = new IllegalStateException("partial feed startup");
        doThrow(failure).doNothing().when(feed).start();

        assertSame(failure, assertThrows(IllegalStateException.class, runtime::start));

        verify(feed).stop();
        assertFalse(runtime.isRunning());
        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                leadership::requireLiveSubmissionReady);
        runtime.start();
        assertTrue(runtime.isRunning());
        assertDoesNotThrow(leadership::requireLiveSubmissionReady);
        runtime.stop();
    }

    @Test
    void startupFailureIsPreservedWhenCleanupAlsoFails() {
        RuntimeException startupFailure = new IllegalStateException("partial feed startup");
        RuntimeException cleanupFailure = new IllegalArgumentException("feed cleanup failed");
        doThrow(startupFailure).when(feed).start();
        doThrow(cleanupFailure).when(feed).stop();

        assertSame(startupFailure, assertThrows(IllegalStateException.class, runtime::start));

        assertEquals(List.of(cleanupFailure), List.of(startupFailure.getSuppressed()));
        assertFalse(runtime.isRunning());
        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                leadership::requireLiveSubmissionReady);
    }

    @Test
    void feedCleanupStillRunsWhenLeadershipCleanupFails() {
        TradingLeadershipService failingLeadership = mock(TradingLeadershipService.class);
        TradingMarketDataRuntime failingRuntime = new TradingMarketDataRuntime(feed, failingLeadership);
        RuntimeException failure = new IllegalStateException("leadership cleanup failed");
        doThrow(failure).when(failingLeadership).stop();
        failingRuntime.start();

        assertSame(failure, assertThrows(IllegalStateException.class, failingRuntime::stop));

        verify(feed).stop();
        assertFalse(failingRuntime.isRunning());
    }

    private static TradingLeadershipService leadership() {
        TradingProperties properties = new TradingProperties();
        properties.setEnabled(true);
        // Isolate runtime admission from distributed lease verification without any external I/O.
        properties.getLeadership().setEnabled(false);
        properties.getLeadership().setRequiredForLive(false);
        return new TradingLeadershipService(mock(TradingLeaderLeaseRepository.class), properties,
                mock(TaskScheduler.class), new SimpleMeterRegistry(), "runtime-test");
    }
}
