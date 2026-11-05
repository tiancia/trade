package com.trade.trading.application.runtime;

import com.trade.trading.port.TradingLeaderLeaseRepository;
import com.trade.trading.domain.model.TradingLeaderLease;
import com.trade.trading.infrastructure.config.TradingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

        assertFalse(service.runIfRunningLeader("paper-decision", runs::incrementAndGet));
        service.start();
        assertTrue(service.runIfRunningLeader("paper-decision", runs::incrementAndGet));
        assertEquals(1, runs.get());
        assertThrows(
                TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveSubmissionLeadership
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

        assertTrue(first.runIfRunningLeader("decision", firstRuns::incrementAndGet));
        assertFalse(second.runIfRunningLeader("decision", secondRuns::incrementAndGet));
        assertEquals(1, firstRuns.get());
        assertEquals(0, secondRuns.get());
        assertTrue(first.status().leader());
        assertFalse(second.status().leader());

        first.stop();

        assertTrue(second.runIfRunningLeader("decision", secondRuns::incrementAndGet));
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
                service::requireLiveSubmissionLeadership
        );
        assertFalse(service.status().leader());
        assertEquals(1, service.status().consecutiveFailures());
    }

    @Test
    void canceledHeartbeatCannotReacquireAReleasedLeaseOrRenewAfterRestart() {
        TradingProperties properties = leadershipProperties();
        InMemoryLeaseRepository repository = new InMemoryLeaseRepository();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Runnable> heartbeats = new ArrayList<>();
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .thenAnswer(invocation -> {
                    heartbeats.add(invocation.getArgument(0));
                    return mock(java.util.concurrent.ScheduledFuture.class);
                });
        var first = new TradingLeadershipService(repository,
                properties, scheduler, new SimpleMeterRegistry(), "instance-a");
        first.start();
        first.stop();

        // cancel(false) cannot recall a callback already waiting for the service monitor.
        heartbeats.getFirst().run();

        assertFalse(first.status().running());
        assertFalse(first.isLeader());
        assertEquals(1, repository.acquisitions);
        var second = service(properties, repository, "instance-b");
        second.start();
        assertTrue(second.isLeader());
        second.stop();
        first.start();
        int afterRestart = repository.acquisitions;
        heartbeats.getFirst().run();
        assertEquals(afterRestart, repository.acquisitions);
        heartbeats.get(1).run();
        assertEquals(afterRestart + 1, repository.acquisitions);
    }

    @Test
    void stoppedCallbacksCannotReacquireButManualRecoveryRemainsAvailable() {
        TradingProperties properties = leadershipProperties();
        InMemoryLeaseRepository repository = new InMemoryLeaseRepository();
        TradingLeadershipService service = service(properties, repository);
        AtomicInteger runs = new AtomicInteger();
        service.start();
        service.stop();

        assertFalse(service.runIfRunningLeader("decision", runs::incrementAndGet));
        assertFalse(service.runIfRunningLeader("event-scan", runs::incrementAndGet));
        assertFalse(service.runIfRunningLeader("reconciliation", runs::incrementAndGet));
        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveSubmissionLeadership);
        assertEquals(0, runs.get());
        assertEquals(1, repository.acquisitions);

        properties.setEnabled(false);
        assertTrue(service.runIfLeader("manual-reconciliation", runs::incrementAndGet));
        assertDoesNotThrow(service::requireLiveLeadership);
        assertEquals(1, runs.get());
        assertFalse(service.status().running());
        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveSubmissionLeadership);
    }

    @Test
    void runtimeAndModuleGatesStillApplyWhenLiveLeaseRequirementIsDisabled() {
        TradingProperties properties = leadershipProperties();
        properties.getLeadership().setRequiredForLive(false);
        TradingLeadershipService service = service(properties, new InMemoryLeaseRepository());
        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveSubmissionLeadership);
        service.start();
        assertDoesNotThrow(service::requireLiveSubmissionLeadership);
        properties.setEnabled(false);
        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveSubmissionLeadership);
        properties.setEnabled(true);
        service.stop();
        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveSubmissionLeadership);
        service.start();
        assertDoesNotThrow(service::requireLiveSubmissionLeadership);
    }

    @Test
    void finalSubmissionCheckRejectsAnExpiredLeaseWithoutReacquiring() {
        TradingProperties properties = leadershipProperties();
        TradingLeaderLeaseRepository repository = mock(TradingLeaderLeaseRepository.class);
        TradingLeaderLease lease = mock(TradingLeaderLease.class);
        when(lease.ownerId()).thenReturn("instance-a");
        when(lease.leaseUntil()).thenReturn(Instant.now().plusSeconds(60));
        when(repository.acquireOrRenew(any(), any(), any())).thenReturn(lease);
        TradingLeadershipService service = service(properties, repository, "instance-a");
        service.start();
        assertDoesNotThrow(service::requireLiveSubmissionReady);

        when(lease.leaseUntil()).thenReturn(Instant.EPOCH);

        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveSubmissionReady);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1))
                .acquireOrRenew(any(), any(), any());
    }

    @Test
    void finalSubmissionCheckStillRequiresRunningEnabledModuleWithoutLeaseRequirement() {
        TradingProperties properties = leadershipProperties();
        properties.getLeadership().setRequiredForLive(false);
        TradingLeadershipService service = service(properties, new InMemoryLeaseRepository());
        service.start();
        assertDoesNotThrow(service::requireLiveSubmissionReady);
        properties.setEnabled(false);
        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveSubmissionReady);
        properties.setEnabled(true);
        service.stop();
        assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                service::requireLiveSubmissionReady);
    }

    @Test
    void finalSubmissionCheckDoesNotWaitForStoppedRuntimeToReleaseItsDatabaseLease() throws Exception {
        TradingProperties properties = leadershipProperties();
        TradingLeaderLeaseRepository repository = mock(TradingLeaderLeaseRepository.class);
        when(repository.acquireOrRenew(any(), any(), any())).thenReturn(new TradingLeaderLease(
                "test", "instance-a", Instant.now().plusSeconds(60), 1, Instant.now()));
        var releaseStarted = new java.util.concurrent.CountDownLatch(1);
        var allowRelease = new java.util.concurrent.CountDownLatch(1);
        when(repository.release(any(), any())).thenAnswer(invocation -> {
            releaseStarted.countDown();
            assertTrue(allowRelease.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return true;
        });
        TradingLeadershipService service = service(properties, repository, "instance-a");
        service.start();
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var stopped = workers.submit(service::stop);
            assertTrue(releaseStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var admission = workers.submit(() -> assertThrows(
                    TradingLeadershipService.TradingLeadershipUnavailableException.class,
                    service::requireLiveSubmissionReady));
            assertTrue(admission.get(1, java.util.concurrent.TimeUnit.SECONDS).getMessage().contains("stopped"));
            allowRelease.countDown();
            stopped.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            allowRelease.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void stopClosesAdmissionWhileWaitingForAnInFlightHeartbeat() throws Exception {
        TradingProperties properties = leadershipProperties();
        TradingLeaderLeaseRepository repository = mock(TradingLeaderLeaseRepository.class);
        TradingLeaderLease lease = new TradingLeaderLease(
                "test", "instance-a", Instant.now().plusSeconds(60), 1, Instant.now());
        var renewalStarted = new java.util.concurrent.CountDownLatch(1);
        var allowRenewal = new java.util.concurrent.CountDownLatch(1);
        when(repository.acquireOrRenew(any(), any(), any())).thenReturn(lease).thenAnswer(invocation -> {
            renewalStarted.countDown();
            assertTrue(allowRenewal.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return lease;
        });
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Runnable> heartbeats = new ArrayList<>();
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .thenAnswer(invocation -> {
                    heartbeats.add(invocation.getArgument(0));
                    return mock(java.util.concurrent.ScheduledFuture.class);
                });
        TradingLeadershipService service = new TradingLeadershipService(repository, properties,
                scheduler, new SimpleMeterRegistry(), "instance-a");
        service.start();
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        var stopResult = new java.util.concurrent.FutureTask<Void>(() -> { service.stop(); return null; });
        Thread stopper = new Thread(stopResult, "leadership-stop-test");
        try {
            var renewing = workers.submit(heartbeats.getFirst());
            assertTrue(renewalStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            stopper.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (stopper.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(Thread.State.BLOCKED, stopper.getState(), "stop is waiting for heartbeat cleanup");
            assertThrows(TradingLeadershipService.TradingLeadershipUnavailableException.class,
                    service::requireLiveSubmissionReady);
            allowRenewal.countDown();
            renewing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            stopResult.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(service.status().running());
            assertFalse(service.isLeader());
        } finally {
            allowRenewal.countDown();
            stopper.join(5_000);
            workers.shutdownNow();
            service.stop();
        }
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
        private int acquisitions;

        @Override
        public synchronized TradingLeaderLease acquireOrRenew(
                String leaseName,
                String ownerId,
                Duration leaseDuration
        ) {
            acquisitions++;
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
