package com.trade.weibo.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.weibo.application.port.WeiboPostRepository;
import com.trade.weibo.domain.model.HotEvent;
import com.trade.weibo.domain.model.WeiboPost;
import com.trade.weibo.domain.model.WeiboPostHistory;
import com.trade.weibo.domain.model.WeiboPostStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisWeiboPostRepository implements WeiboPostRepository {
    private final WeiboPostMapper mapper;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public MyBatisWeiboPostRepository(WeiboPostMapper mapper) { this.mapper = mapper; }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean reserveGeneration(WeiboPost post, Instant dayStart, int dailyLimit) {
        lock(post.targetUid());
        if (mapper.findByEvent(post.targetUid(), post.event().key()) != null
                || mapper.countGenerated(post.targetUid(), time(dayStart)) >= dailyLimit) return false;
        WeiboPostRow row = row(post);
        mapper.insert(row);
        mapper.insertHistory(row);
        return true;
    }

    @Override
    public Optional<WeiboPost> find(String id) {
        return Optional.ofNullable(mapper.find(id)).map(this::post);
    }

    @Override
    public List<WeiboPost> recent(int limit) { return mapper.recent(bound(limit)).stream().map(this::post).toList(); }

    @Override
    public List<WeiboPost> actionable(int limit) {
        return mapper.actionable(bound(limit)).stream().map(this::post).toList();
    }

    @Override
    public List<WeiboPostHistory> history(String id) {
        return mapper.history(id).stream().map(row -> new WeiboPostHistory(row.getRevision(),
                row.getContentVersion(), WeiboPostStatus.valueOf(row.getStatus()), row.getBody(),
                row.getReviewer(), row.getReviewReason(), row.getAttemptId(), row.getWeiboId(),
                row.getLastError(), instant(row.getUpdatedAt()))).toList();
    }

    @Override
    @Transactional
    public boolean save(WeiboPost changed, long expectedRevision) {
        return updateWithHistory(changed, expectedRevision);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean claimPublishing(WeiboPost claimed, long expectedRevision, Instant dayStart,
                                   int dailyLimit, Duration minInterval) {
        lock(claimed.targetUid());
        Timestamp latest = mapper.lastAttempt(claimed.targetUid());
        if (mapper.countAttempts(claimed.targetUid(), time(dayStart)) >= dailyLimit
                || (latest != null && latest.toInstant().plus(minInterval).isAfter(claimed.updatedAt()))) {
            return false;
        }
        if (!updateWithHistory(claimed, expectedRevision)) return false;
        mapper.insertAttempt(row(claimed));
        return true;
    }

    @Override
    @Transactional
    public boolean finishPublishing(WeiboPost finished, long expectedRevision) {
        if (!updateWithHistory(finished, expectedRevision)) return false;
        mapper.finishAttempt(row(finished));
        return true;
    }

    private boolean updateWithHistory(WeiboPost changed, long expectedRevision) {
        if (changed.revision() != expectedRevision + 1) throw new IllegalArgumentException("Invalid revision");
        WeiboPostRow row = row(changed);
        if (mapper.update(row, expectedRevision) != 1) return false;
        mapper.insertHistory(row);
        return true;
    }

    private void lock(String uid) {
        // All generation/publishing quota checks for an account run under the same row lock.
        mapper.ensureAccountGate(uid);
        mapper.lockAccountGate(uid);
    }

    private WeiboPostRow row(WeiboPost post) {
        try {
            return new WeiboPostRow().setId(post.id()).setTargetUid(post.targetUid())
                    .setEventKey(post.event().key()).setEventJson(json.writeValueAsString(post.event()))
                    .setBody(post.body()).setReviewNote(post.reviewNote()).setContentVersion(post.contentVersion())
                    .setRevision(post.revision()).setStatus(post.status().name()).setCreatedAt(time(post.createdAt()))
                    .setUpdatedAt(time(post.updatedAt())).setExpiresAt(time(post.expiresAt()))
                    .setReviewer(post.reviewer()).setReviewedAt(time(post.reviewedAt())).setReviewReason(post.reviewReason())
                    .setAttemptId(post.attemptId()).setPublishStartedAt(time(post.publishStartedAt()))
                    .setWeiboId(post.weiboId()).setLastError(post.lastError());
        } catch (Exception e) { throw new IllegalStateException("Cannot encode Weibo post"); }
    }

    private WeiboPost post(WeiboPostRow row) {
        try {
            return new WeiboPost(row.getId(), row.getTargetUid(), json.readValue(row.getEventJson(), HotEvent.class),
                    row.getBody(), row.getReviewNote(), row.getContentVersion(), row.getRevision(),
                    WeiboPostStatus.valueOf(row.getStatus()), instant(row.getCreatedAt()), instant(row.getUpdatedAt()),
                    instant(row.getExpiresAt()), row.getReviewer(), instant(row.getReviewedAt()), row.getReviewReason(),
                    row.getAttemptId(), instant(row.getPublishStartedAt()), row.getWeiboId(), row.getLastError());
        } catch (Exception e) { throw new IllegalStateException("Cannot restore Weibo post"); }
    }

    private static int bound(int limit) { return Math.max(1, Math.min(100, limit)); }
    private static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
