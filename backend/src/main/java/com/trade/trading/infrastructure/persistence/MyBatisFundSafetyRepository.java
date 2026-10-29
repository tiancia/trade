package com.trade.trading.infrastructure.persistence;

import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;

import com.trade.trading.port.FundSafetyRepository;
import com.trade.trading.domain.risk.FundSafetyState;
import com.trade.trading.domain.risk.FundSafetyStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ConcurrentModificationException;

@Component
public class MyBatisFundSafetyRepository implements FundSafetyRepository {
    private final Cache initializedRows = new TransactionAwareCacheDecorator(
            new ConcurrentMapCache("initialized-rows"));

    private final FundSafetyMapper mapper;

    public MyBatisFundSafetyRepository(FundSafetyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public FundSafetyState getOrCreate(String accountScope) {
        boolean liveScope = "live".equalsIgnoreCase(accountScope);
        Instant now = Instant.now();
        initializeOnce(accountScope, () -> mapper.insertIfAbsent(new FundSafetyStateRow()
                .setAccountScope(accountScope)
                .setStatus(liveScope ? FundSafetyStatus.HALTED : FundSafetyStatus.ACTIVE)
                .setReason(liveScope
                        ? "Initial LIVE safety state requires successful reconciliation and operator resume"
                        : null)
                .setSource(liveScope ? "bootstrap" : null)
                .setHaltedAt(liveScope ? now : null)
                .setUpdatedAt(now)
                .setVersion(0)), () -> mapper.lock(accountScope) != null);
        return required(accountScope);
    }

    @Override
    @Transactional
    public FundSafetyState halt(String accountScope, String source, String reason, Instant haltedAt) {
        getOrCreate(accountScope);
        mapper.halt(accountScope, source, reason, haltedAt, Instant.now());
        return required(accountScope);
    }

    @Override
    @Transactional
    public FundSafetyState resume(
            String accountScope,
            long expectedVersion,
            String resumeReason,
            Instant resumedAt
    ) {
        if (mapper.resume(accountScope, expectedVersion, resumeReason, resumedAt, Instant.now()) != 1) {
            FundSafetyState current = getOrCreate(accountScope);
            throw new ConcurrentModificationException(
                    "Fund safety revision changed from " + expectedVersion + " to " + current.getVersion()
            );
        }
        return required(accountScope);
    }

    @Override
    @Transactional
    public FundSafetyState recordActionError(String accountScope, String error) {
        getOrCreate(accountScope);
        mapper.recordActionError(accountScope, error, Instant.now());
        return required(accountScope);
    }

    private FundSafetyState required(String accountScope) {
        FundSafetyStateRow row = mapper.lock(accountScope);
        if (row == null) {
            throw new IllegalStateException("Fund safety state not found: " + accountScope);
        }
        return new FundSafetyState()
                .setAccountScope(row.getAccountScope())
                .setStatus(row.getStatus())
                .setReason(row.getReason())
                .setSource(row.getSource())
                .setResumeReason(row.getResumeReason())
                .setLastActionError(row.getLastActionError())
                .setHaltedAt(row.getHaltedAt())
                .setResumedAt(row.getResumedAt())
                .setUpdatedAt(row.getUpdatedAt())
                .setVersion(row.getVersion());
    }
    /** Memoize only committed existence; values and transactional locks remain in MySQL. */
    private void initializeOnce(Object key, Runnable insert, java.util.function.BooleanSupplier exists) {
        if (initializedRows.get(key) != null) {
            if (exists.getAsBoolean()) {
                return;
            }
            // A restored or manually cleared database must not retain an old existence marker.
            initializedRows.evictIfPresent(key);
        }
        insert.run();
        // Eviction only repeats a safe insert; bound account and replay identities.
        if (((java.util.Map<?, ?>) initializedRows.getNativeCache()).size() >= 1024) {
            initializedRows.clear();
        }
        initializedRows.put(key, Boolean.TRUE);
    }
}
