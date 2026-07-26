package com.trade.trading.application;

import com.trade.trading.application.port.TradingLeaderLeaseRepository;
import com.trade.trading.config.TradingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains a database-backed single-writer lease for the trading account.
 *
 * <p>Followers keep polling so one can take over after the current lease
 * expires. A dedicated heartbeat runs outside the serialized trading loops,
 * preventing a slow market decision from accidentally allowing the lease to
 * expire. Loss of the database or lease fails closed for live submissions.</p>
 */
@Component
public class TradingLeadershipService {
    private static final Logger log = LoggerFactory.getLogger(TradingLeadershipService.class);

    private final TradingLeaderLeaseRepository repository;
    private final TradingProperties properties;
    private final TaskScheduler scheduler;
    private final MeterRegistry meterRegistry;
    private final String ownerId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean leader = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> heartbeat;
    private volatile TradingLeaderLease currentLease;
    private volatile Instant lastAttemptAt;
    private volatile Instant lastSuccessfulRenewalAt;
    private volatile int consecutiveFailures;
    private volatile String lastError;

    @Autowired
    public TradingLeadershipService(
            TradingLeaderLeaseRepository repository,
            TradingProperties properties,
            @Qualifier("automationTaskScheduler") TaskScheduler scheduler,
            MeterRegistry meterRegistry
    ) {
        this(repository, properties, scheduler, meterRegistry, resolveOwnerId(properties));
    }

    TradingLeadershipService(
            TradingLeaderLeaseRepository repository,
            TradingProperties properties,
            TaskScheduler scheduler,
            MeterRegistry meterRegistry,
            String ownerId
    ) {
        this.repository = repository;
        this.properties = properties;
        this.scheduler = scheduler;
        this.meterRegistry = meterRegistry;
        this.ownerId = normalize(ownerId, "instance id");
        Gauge.builder("trade.trading.leadership.enabled", this, value -> value.isEnabled() ? 1 : 0)
                .description("Whether database-backed trading leadership is enabled")
                .register(meterRegistry);
        Gauge.builder("trade.trading.leadership.active", this, value -> value.isLeader() ? 1 : 0)
                .description("Whether this application instance currently owns the trading lease")
                .register(meterRegistry);
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (!isEnabled()) {
            log.warn(
                    "Database trading leadership is disabled; PAPER remains available, "
                            + "but LIVE submissions require leadership by default"
            );
            return;
        }
        try {
            validateConfiguration();
            refreshSafely("start");
            heartbeat = scheduler.scheduleWithFixedDelay(
                    () -> refreshSafely("heartbeat"),
                    Duration.ofMillis(properties.getLeadership().getHeartbeatIntervalMs())
            );
        } catch (RuntimeException e) {
            running.set(false);
            leader.set(false);
            releaseQuietly();
            throw e;
        }
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        releaseQuietly();
        leader.set(false);
    }

    /**
     * Runs one scheduled operation on the current leader. Leadership disabled
     * means single-instance compatibility mode; the live broker still enforces
     * {@link #requireLiveLeadership()} before any external order submission.
     */
    public boolean runIfLeader(String operation, Runnable action) {
        if (!isEnabled()) {
            action.run();
            return true;
        }
        if (!isLeader() && !refreshSafely("before-" + metricValue(operation))) {
            counter("operation.skipped", metricValue(operation)).increment();
            log.debug(
                    "Trading operation skipped on follower: operation={}, lease={}, owner={}",
                    operation,
                    properties.getLeadership().getLeaseName(),
                    currentLease == null ? null : currentLease.ownerId()
            );
            return false;
        }
        action.run();
        return true;
    }

    /** Enforces an up-to-date lease immediately before a real OKX submission. */
    public void requireLiveLeadership() {
        if (!properties.getLeadership().isRequiredForLive()) {
            return;
        }
        if (!isEnabled()) {
            throw new TradingLeadershipUnavailableException(
                    "Live OKX order blocked: enable trade.trading.leadership after applying "
                            + "the leader-lease database migration"
            );
        }
        validateConfiguration();
        // Always round-trip to the database here. A locally cached expiry is
        // insufficient when hosts have clock skew or the process was paused.
        if (!refreshSafely("live-submit")) {
            throw unavailable();
        }
    }

    public TradingLeadershipStatus status() {
        TradingLeaderLease lease = currentLease;
        return new TradingLeadershipStatus(
                isEnabled(),
                running.get(),
                isLeader(),
                properties.getLeadership().getLeaseName(),
                ownerId,
                lease == null ? null : lease.ownerId(),
                lease == null ? null : lease.fencingToken(),
                lease == null ? null : lease.leaseUntil(),
                lastAttemptAt,
                lastSuccessfulRenewalAt,
                consecutiveFailures,
                lastError
        );
    }

    public boolean isLeader() {
        TradingLeaderLease lease = currentLease;
        boolean active = leader.get()
                && lease != null
                && ownerId.equals(lease.ownerId())
                && lease.leaseUntil() != null
                && lease.leaseUntil().isAfter(Instant.now());
        if (!active) {
            leader.set(false);
        }
        return active;
    }

    private synchronized boolean refreshSafely(String trigger) {
        if (!isEnabled()) {
            return true;
        }
        lastAttemptAt = Instant.now();
        try {
            TradingLeaderLease previous = currentLease;
            TradingLeaderLease lease = repository.acquireOrRenew(
                    normalize(properties.getLeadership().getLeaseName(), "lease name"),
                    ownerId,
                    Duration.ofMillis(properties.getLeadership().getLeaseDurationMs())
            );
            currentLease = lease;
            boolean ownsLease = lease != null
                    && ownerId.equals(lease.ownerId())
                    && lease.leaseUntil() != null
                    && lease.leaseUntil().isAfter(Instant.now());
            leader.set(ownsLease);
            consecutiveFailures = 0;
            lastError = null;
            if (ownsLease) {
                lastSuccessfulRenewalAt = Instant.now();
                String outcome = previous == null || !ownerId.equals(previous.ownerId())
                        ? "acquired"
                        : "renewed";
                counter("refresh." + outcome, metricValue(trigger)).increment();
            } else {
                counter("refresh.follower", metricValue(trigger)).increment();
            }
            return ownsLease;
        } catch (RuntimeException e) {
            leader.set(false);
            consecutiveFailures++;
            lastError = e.getMessage();
            counter("refresh.failed", metricValue(trigger)).increment();
            log.error(
                    "Trading leadership refresh failed: lease={}, owner={}, trigger={}, err={}",
                    properties.getLeadership().getLeaseName(),
                    ownerId,
                    trigger,
                    e.getMessage()
            );
            return false;
        }
    }

    private void releaseQuietly() {
        if (!isEnabled() || currentLease == null || !ownerId.equals(currentLease.ownerId())) {
            return;
        }
        try {
            if (repository.release(properties.getLeadership().getLeaseName(), ownerId)) {
                counter("released", "stop").increment();
            }
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            log.warn(
                    "Trading leadership release failed; lease will expire automatically: lease={}, owner={}, err={}",
                    properties.getLeadership().getLeaseName(),
                    ownerId,
                    e.getMessage()
            );
        }
    }

    private boolean isEnabled() {
        return properties.getLeadership() != null && properties.getLeadership().isEnabled();
    }

    private void validateConfiguration() {
        long durationMs = properties.getLeadership().getLeaseDurationMs();
        long heartbeatMs = properties.getLeadership().getHeartbeatIntervalMs();
        if (heartbeatMs <= 0) {
            throw new IllegalStateException("Trading leadership heartbeat-interval-ms must be positive");
        }
        if (durationMs < heartbeatMs * 3) {
            throw new IllegalStateException(
                    "Trading leadership lease-duration-ms must be at least three times heartbeat-interval-ms"
            );
        }
        normalize(properties.getLeadership().getLeaseName(), "lease name");
    }

    private TradingLeadershipUnavailableException unavailable() {
        TradingLeaderLease lease = currentLease;
        String message = lastError == null
                ? "Trading lease is owned by another instance"
                : "Trading lease refresh failed: " + lastError;
        return new TradingLeadershipUnavailableException(
                "Live OKX order blocked: " + message
                        + ", lease=" + properties.getLeadership().getLeaseName()
                        + ", currentOwner=" + (lease == null ? null : lease.ownerId())
        );
    }

    private Counter counter(String outcome, String trigger) {
        return Counter.builder("trade.trading.leadership.operations")
                .description("Database-backed trading leadership operations")
                .tag("outcome", outcome)
                .tag("trigger", trigger)
                .register(meterRegistry);
    }

    private static String resolveOwnerId(TradingProperties properties) {
        String configured = properties.getLeadership() == null
                ? null
                : properties.getLeadership().getInstanceId();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String host = firstText(
                System.getenv("HOSTNAME"),
                System.getenv("COMPUTERNAME"),
                localHostName(),
                "unknown-host"
        );
        return (host + "-" + ProcessHandle.current().pid() + "-"
                + UUID.randomUUID().toString().substring(0, 8))
                .replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "unknown";
    }

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Trading leadership " + field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("Trading leadership " + field + " must not exceed 128 characters");
        }
        return normalized;
    }

    private static String metricValue(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_");
    }

    public static final class TradingLeadershipUnavailableException extends IllegalStateException {
        public TradingLeadershipUnavailableException(String message) {
            super(message);
        }
    }
}
