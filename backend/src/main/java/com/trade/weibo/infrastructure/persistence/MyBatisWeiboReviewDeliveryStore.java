package com.trade.weibo.infrastructure.persistence;

import com.trade.weibo.application.port.WeiboReviewDeliveryStore;
import com.trade.weibo.domain.model.WeiboReviewDelivery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.OptionalLong;

@Repository
public class MyBatisWeiboReviewDeliveryStore implements WeiboReviewDeliveryStore {
    private final WeiboReviewDeliveryMapper mapper;

    public MyBatisWeiboReviewDeliveryStore(WeiboReviewDeliveryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean reserve(WeiboReviewDelivery delivery) {
        try {
            return mapper.insert(row(delivery)) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public Optional<WeiboReviewDelivery> find(long botId, String id) {
        return Optional.ofNullable(mapper.find(botId, id)).map(MyBatisWeiboReviewDeliveryStore::delivery);
    }

    @Override
    public Optional<WeiboReviewDelivery> findForPost(long botId, String postId, int version) {
        return Optional.ofNullable(mapper.findForPost(botId, postId, version)).map(MyBatisWeiboReviewDeliveryStore::delivery);
    }

    @Override
    public boolean markSent(long botId, String id, long messageId) {
        return mapper.markSent(botId, id, messageId) == 1;
    }

    @Override
    public void markUnknown(long botId, String id) {
        mapper.markUnknown(botId, id);
    }

    @Override
    public boolean recordDecision(long botId, String id, String callbackId, boolean approved, String reviewer, Instant decidedAt) {
        if (callbackId == null || callbackId.isBlank() || decidedAt == null) {
            throw new IllegalArgumentException("Review callback identity and time are required");
        }
        return mapper.recordDecision(botId, id, callbackId, approved, reviewer, time(decidedAt)) == 1;
    }

    @Override
    public void finishDecision(long botId, String id, boolean applied) {
        mapper.finishDecision(botId, id, applied ? "APPLIED" : "INVALID");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OptionalLong acquirePolling(long botId, String owner, Instant now, Instant until) {
        validateLease(owner, now, until);
        mapper.ensurePolling(botId);
        if (mapper.acquirePolling(botId, owner, time(now), time(until)) != 1) return OptionalLong.empty();
        return OptionalLong.of(mapper.pollingOffset(botId));
    }

    @Override
    public boolean renewPolling(long botId, String owner, Instant now, Instant until) {
        validateLease(owner, now, until);
        return mapper.renewPolling(botId, owner, time(now), time(until)) == 1;
    }

    @Override
    public boolean advancePolling(long botId, String owner, long nextOffset, Instant now, Instant until) {
        validateLease(owner, now, until);
        if (nextOffset < 0) throw new IllegalArgumentException("Polling offset must be nonnegative");
        return mapper.advancePolling(botId, owner, nextOffset, time(now), time(until)) == 1;
    }

    @Override
    public void releasePolling(long botId, String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Polling owner is required");
        mapper.releasePolling(botId, owner);
    }

    private static void validateLease(String owner, Instant now, Instant until) {
        if (owner == null || owner.isBlank() || now == null || until == null
                || !until.truncatedTo(ChronoUnit.MICROS).isAfter(now.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("Polling owner and a future lease deadline are required");
        }
    }

    private static WeiboReviewDeliveryRow row(WeiboReviewDelivery delivery) {
        return new WeiboReviewDeliveryRow().setId(delivery.id()).setBotId(delivery.botId()).setPostId(delivery.postId())
                .setVersion(delivery.version()).setChatId(delivery.chatId()).setMessageId(delivery.messageId())
                .setStatus(delivery.status()).setExpiresAt(time(delivery.expiresAt())).setCallbackId(delivery.callbackId())
                .setApproved(delivery.approved()).setReviewer(delivery.reviewer()).setDecidedAt(time(delivery.decidedAt()))
                .setDecisionStatus(delivery.decisionStatus());
    }

    private static WeiboReviewDelivery delivery(WeiboReviewDeliveryRow row) {
        return new WeiboReviewDelivery(row.getId(), row.getBotId(), row.getPostId(), row.getVersion(), row.getChatId(),
                row.getMessageId(), row.getStatus(), instant(row.getExpiresAt()), row.getCallbackId(), row.getApproved(),
                row.getReviewer(), instant(row.getDecidedAt()), row.getDecisionStatus());
    }

    private static Timestamp time(Instant value) {
        return value == null ? null : Timestamp.from(value.truncatedTo(ChronoUnit.MICROS));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
