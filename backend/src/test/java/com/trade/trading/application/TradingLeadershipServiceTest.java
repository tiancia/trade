package com.trade.trading.application;

import com.trade.trading.application.port.TradingLeaderLeaseRepository;
import com.trade.trading.config.TradingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradingLeadershipServiceTest {

    @Test
    void disabledLeadershipKeepsPaperCompatibleButBlocksLiveSubmission() {
        TradingProperties properties = new TradingProperties();
        properties.getLeadership().setEnabled(false);
        AtomicInteger runs = new AtomicInteger();
        TradingLeadershipService service = service(properties, new InMemoryLeaseRepository());

        assertTrue(service.runIfLeader("paper-decision", runs::incrementAndGet));
        assertEquals(1, runs.get());
        assertThrows(
                TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveLeadership
        );
    }

    @Test
    void onlyLeaseOwnerRunsAndStopReleasesForImmediateFailover() {
        TradingProperties properties = leadershipProperties();
        InMemoryLeaseRepository repository = new InMemoryLeaseRepository();
        TradingLeadershipService first = service(properties, repository, "instance-a");
        TradingLeadershipService second = service(properties, repository, "instance-b");
        AtomicInteger firstRuns = new AtomicInteger();
        AtomicInteger secondRuns = new AtomicInteger();

        first.start();
        second.start();

        assertTrue(first.runIfLeader("decision", firstRuns::incrementAndGet));
        assertFalse(second.runIfLeader("decision", secondRuns::incrementAndGet));
        assertEquals(1, firstRuns.get());
        assertEquals(0, secondRuns.get());
        assertTrue(first.status().leader());
        assertFalse(second.status().leader());

        first.stop();

        assertTrue(second.runIfLeader("decision", secondRuns::incrementAndGet));
        assertEquals(1, secondRuns.get());
        assertTrue(second.status().leader());
        assertEquals(2L, second.status().fencingToken());
    }

    @Test
    void liveSubmissionRefreshFailureFailsClosedBeforeExternalOrder() {
        TradingProperties properties = leadershipProperties();
        InMemoryLeaseRepository repository = new InMemoryLeaseRepository();
        repository.shortLease = true;
        TradingLeadershipService service = service(properties, repository, "instance-a");
        service.start();
        repository.fail = true;

        assertThrows(
                TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveLeadership
        );
        assertFalse(service.status().leader());
        assertEquals(1, service.status().consecutiveFailures());
    }

    private static TradingProperties leadershipProperties() {
        TradingProperties properties = new TradingProperties();
        properties.getLeadership().setEnabled(true);
        properties.getLeadership().setLeaseDurationMs(30_000L);
        properties.getLeadership().setHeartbeatIntervalMs(5_000L);
        return properties;
    }

    private static TradingLeadershipService service(
            TradingProperties properties,
            TradingLeaderLeaseRepository repository
    ) {
        return service(properties, repository, "test-instance");
    }

    private static TradingLeadershipService service(
            TradingProperties properties,
            TradingLeaderLeaseRepository repository,
            String ownerId
    ) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .thenAnswer(invocation -> mock(java.util.concurrent.ScheduledFuture.class));
        return new TradingLeadershipService(
                repository,
                properties,
                scheduler,
                new SimpleMeterRegistry(),
                ownerId
        );
    }

    private static final class InMemoryLeaseRepository implements TradingLeaderLeaseRepository {
        private TradingLeaderLease lease;
        private boolean fail;
        private boolean shortLease;

        @Override
        public synchronized TradingLeaderLease acquireOrRenew(
                String leaseName,
                String ownerId,
                Duration leaseDuration
        ) {
            if (fail) {
                throw new IllegalStateException("database unavailable");
            }
            Instant now = Instant.now();
            boolean ownerChanged = lease == null
                    || lease.leaseUntil() == null
                    || !lease.leaseUntil().isAfter(now);
            if (lease != null && !ownerId.equals(lease.ownerId()) && !ownerChanged) {
                return lease;
            }
            long token = lease == null ? 1L : ownerChanged && !ownerId.equals(lease.ownerId())
                    ? lease.fencingToken() + 1L
                    : lease.fencingToken();
            Duration actualDuration = shortLease ? Duration.ofMillis(50L) : leaseDuration;
            lease = new TradingLeaderLease(
                    leaseName,
                    ownerId,
                    now.plus(actualDuration),
                    token,
                    now
            );
            return lease;
        }

        @Override
        public synchronized TradingLeaderLease find(String leaseName) {
            return lease;
        }

        @Override
        public synchronized boolean release(String leaseName, String ownerId) {
            if (lease == null || !ownerId.equals(lease.ownerId())) {
                return false;
            }
            lease = new TradingLeaderLease(
                    lease.leaseName(),
                    lease.ownerId(),
                    Instant.now().minusMillis(1L),
                    lease.fencingToken(),
                    Instant.now()
            );
            return true;
        }
    }
}
