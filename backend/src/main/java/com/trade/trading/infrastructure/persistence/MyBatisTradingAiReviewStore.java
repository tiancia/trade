package com.trade.trading.infrastructure.persistence;

import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;

import com.trade.trading.port.TradingAiReviewStore;
import com.trade.trading.domain.model.TradingAiReview;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public class MyBatisTradingAiReviewStore implements TradingAiReviewStore {
    private final Cache initializedRows = new TransactionAwareCacheDecorator(
            new ConcurrentMapCache("initialized-rows"));

    private final TradingAiReviewMapper mapper;

    public MyBatisTradingAiReviewStore(TradingAiReviewMapper mapper) { this.mapper = mapper; }

    @Override @Transactional
    public boolean claim(String scope, String reviewId, Instant now, Instant nextReviewAt, Instant expiresAt) {
        initializeOnce(scope, () -> mapper.insertGuard(scope), () -> mapper.lockGuard(scope) != null);
        if (mapper.lockGuard(scope).isAfter(now)) return false;
        TradingAiReviewRow row = new TradingAiReviewRow();
        row.setReviewId(reviewId);
        row.setScope(scope);
        row.setStartedAt(now);
        row.setExpiresAt(expiresAt);
        mapper.insert(row);
        mapper.advanceReview(scope, nextReviewAt);
        return true;
    }

    @Override @Transactional
    public void capture(String id, String snapshotJson) {
        if (mapper.capture(id, snapshotJson) != 1) throw new IllegalStateException("AI review is no longer analyzing");
    }

    @Override @Transactional
    public void proposed(String id, String snapshotJson, String proposalJson) {
        if (mapper.proposed(id, snapshotJson, proposalJson) != 1) {
            throw new IllegalStateException("AI review is no longer analyzing");
        }
    }

    @Override @Transactional
    public boolean beginApplying(String id, Instant now) { return mapper.beginApplying(id, now) == 1; }

    @Override @Transactional
    public void complete(String id, String outcome, String detail, Instant now) {
        if (mapper.complete(id, outcome, detail, now) != 1) {
            throw new IllegalStateException("AI review is already complete");
        }
    }

    @Override @Transactional
    public boolean claimNotification(String id, Instant now, Instant nextNotificationAt) {
        TradingAiReviewRow row = mapper.find(id);
        if (row == null) return false;
        mapper.lockGuard(row.getScope());
        if (mapper.reserveNotification(id) != 1) return false;
        if (mapper.advanceNotification(row.getScope(), now, nextNotificationAt) != 1) {
            mapper.notification(id, "THROTTLED");
            return false;
        }
        return true;
    }

    @Override @Transactional
    public void notification(String id, String status) { mapper.notification(id, status); }

    @Override @Transactional(readOnly = true)
    public List<TradingAiReview> recent(String scope, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be from 1 to 100");
        return mapper.recent(scope, limit).stream().map(r -> new TradingAiReview(r.getReviewId(), r.getScope(),
                r.getStartedAt(), r.getExpiresAt(), r.getSnapshotJson(), r.getProposalJson(), r.getOutcome(),
                r.getDetail(), r.getNotificationStatus(), r.getCompletedAt())).toList();
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
