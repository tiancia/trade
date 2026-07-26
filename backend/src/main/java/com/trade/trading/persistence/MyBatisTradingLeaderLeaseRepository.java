package com.trade.trading.persistence;

import com.trade.trading.application.TradingLeaderLease;
import com.trade.trading.application.port.TradingLeaderLeaseRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * MySQL lease adapter using database time and an atomic conditional update.
 *
 * <p>The fencing token advances only when ownership changes. It is exposed for
 * auditability; external OKX calls are additionally guarded by a fresh lease
 * check immediately before submission.</p>
 */
@Repository
public class MyBatisTradingLeaderLeaseRepository implements TradingLeaderLeaseRepository {
    private final TradingLeaderLeaseMapper mapper;

    public MyBatisTradingLeaderLeaseRepository(TradingLeaderLeaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public TradingLeaderLease acquireOrRenew(
            String leaseName,
            String ownerId,
            Duration leaseDuration
    ) {
        requireText(leaseName, "leaseName");
        requireText(ownerId, "ownerId");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Instant now = databaseNow();
        Instant leaseUntil = now.plus(leaseDuration);
        mapper.insertIfAbsent(new TradingLeaderLeaseRow()
                .setLeaseName(leaseName)
                .setOwnerId(ownerId)
                .setLeaseUntil(leaseUntil)
                .setFencingToken(1L)
                .setCreatedAt(now)
                .setUpdatedAt(now));
        mapper.acquireOrRenew(leaseName, ownerId, now, leaseUntil);
        return toLease(requireLease(mapper.find(leaseName), leaseName));
    }

    @Override
    public TradingLeaderLease find(String leaseName) {
        requireText(leaseName, "leaseName");
        TradingLeaderLeaseRow row = mapper.find(leaseName);
        return row == null ? null : toLease(row);
    }

    @Override
    @Transactional
    public boolean release(String leaseName, String ownerId) {
        requireText(leaseName, "leaseName");
        requireText(ownerId, "ownerId");
        return mapper.release(leaseName, ownerId, databaseNow()) == 1;
    }

    private Instant databaseNow() {
        Timestamp timestamp = Objects.requireNonNull(
                mapper.currentTime(),
                "Database current timestamp must not be null"
        );
        return timestamp.toInstant();
    }

    private static TradingLeaderLeaseRow requireLease(TradingLeaderLeaseRow row, String leaseName) {
        if (row == null) {
            throw new IllegalStateException("Trading leader lease was not persisted: " + leaseName);
        }
        return row;
    }

    private static TradingLeaderLease toLease(TradingLeaderLeaseRow row) {
        return new TradingLeaderLease(
                row.getLeaseName(),
                row.getOwnerId(),
                row.getLeaseUntil(),
                row.getFencingToken(),
                row.getUpdatedAt()
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
