package com.trade.trading.persistence;

import com.trade.trading.risk.FundSafetyRepository;
import com.trade.trading.risk.FundSafetyState;
import com.trade.trading.risk.FundSafetyStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ConcurrentModificationException;

@Component
public class MyBatisFundSafetyRepository implements FundSafetyRepository {
    private final FundSafetyMapper mapper;

    public MyBatisFundSafetyRepository(FundSafetyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public FundSafetyState getOrCreate(String accountScope) {
        boolean liveScope = "live".equalsIgnoreCase(accountScope);
        Instant now = Instant.now();
        mapper.insertIfAbsent(new FundSafetyStateRow()
                .setAccountScope(accountScope)
                .setStatus(liveScope ? FundSafetyStatus.HALTED : FundSafetyStatus.ACTIVE)
                .setReason(liveScope
                        ? "Initial LIVE safety state requires successful reconciliation and operator resume"
                        : null)
                .setSource(liveScope ? "bootstrap" : null)
                .setHaltedAt(liveScope ? now : null)
                .setUpdatedAt(now)
                .setVersion(0));
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
        FundSafetyStateRow row = mapper.find(accountScope);
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
}
