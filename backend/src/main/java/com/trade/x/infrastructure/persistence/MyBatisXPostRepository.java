package com.trade.x.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.x.application.port.XPostRepository;
import com.trade.x.domain.model.XContentPolicy;
import com.trade.x.domain.model.XPost;
import com.trade.x.domain.model.XPostHistory;
import com.trade.x.domain.model.XPostStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisXPostRepository implements XPostRepository {
    private final XPostMapper mapper;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public MyBatisXPostRepository(XPostMapper mapper) { this.mapper = mapper; }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean reserveGeneration(XPost post, Instant dayStart, int dailyLimit) {
        lock(post.targetUserId());
        if (mapper.findByGenerationKey(post.targetUserId(), post.generationKey()) != null
                || mapper.countGenerated(post.targetUserId(), time(dayStart)) >= dailyLimit) return false;
        XPostRow row = row(post);
        mapper.insert(row);
        mapper.insertHistory(row);
        return true;
    }

    @Override
    public Optional<XPost> find(String id) {
        return Optional.ofNullable(mapper.find(id)).map(this::post);
    }

    @Override
    public List<XPost> recent(int limit) { return mapper.recent(bound(limit)).stream().map(this::post).toList(); }

    @Override
    public List<XPost> actionable(int limit) {
        return mapper.actionable(bound(limit)).stream().map(this::post).toList();
    }

    @Override
    public List<XPostHistory> history(String id) {
        return mapper.history(id).stream().map(row -> new XPostHistory(row.getRevision(),
                row.getContentVersion(), XPostStatus.valueOf(row.getStatus()), row.getBody(),
                row.getReviewer(), row.getReviewReason(), row.getAttemptId(), row.getPostId(),
                row.getLastError(), instant(row.getUpdatedAt()))).toList();
    }

    @Override
    @Transactional
    public boolean save(XPost changed, long expectedRevision) {
        return updateWithHistory(changed, expectedRevision);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean claimPublishing(XPost claimed, long expectedRevision, Instant dayStart,
                                   int dailyLimit, Duration minInterval) {
        lock(claimed.targetUserId());
        Timestamp latest = mapper.lastAttempt(claimed.targetUserId());
        if (mapper.countAttempts(claimed.targetUserId(), time(dayStart)) >= dailyLimit
                || (latest != null && latest.toInstant().plus(minInterval).isAfter(claimed.updatedAt()))) {
            return false;
        }
        if (!updateWithHistory(claimed, expectedRevision)) return false;
        mapper.insertAttempt(row(claimed));
        return true;
    }

    @Override
    @Transactional
    public boolean finishPublishing(XPost finished, long expectedRevision) {
        if (!updateWithHistory(finished, expectedRevision)) return false;
        mapper.finishAttempt(row(finished));
        return true;
    }

    private boolean updateWithHistory(XPost changed, long expectedRevision) {
        if (changed.revision() != expectedRevision + 1) throw new IllegalArgumentException("Invalid revision");
        XPostRow row = row(changed);
        if (mapper.update(row, expectedRevision) != 1) return false;
        mapper.insertHistory(row);
        return true;
    }

    private void lock(String userId) {
        // All generation/publishing quota checks for an account run under the same row lock.
        mapper.ensureAccountGate(userId);
        mapper.lockAccountGate(userId);
    }

    private XPostRow row(XPost post) {
        try {
            return new XPostRow().setId(post.id()).setTargetUserId(post.targetUserId())
                    .setGenerationKey(post.generationKey()).setContentPolicyJson(json.writeValueAsString(post.contentPolicy()))
                    .setBody(post.body()).setReviewNote(post.reviewNote()).setContentVersion(post.contentVersion())
                    .setRevision(post.revision()).setStatus(post.status().name()).setCreatedAt(time(post.createdAt()))
                    .setUpdatedAt(time(post.updatedAt())).setExpiresAt(time(post.expiresAt()))
                    .setReviewer(post.reviewer()).setReviewedAt(time(post.reviewedAt())).setReviewReason(post.reviewReason())
                    .setAttemptId(post.attemptId()).setPublishStartedAt(time(post.publishStartedAt()))
                    .setPostId(post.postId()).setLastError(post.lastError());
        } catch (Exception e) { throw new IllegalStateException("Cannot encode X post"); }
    }

    private XPost post(XPostRow row) {
        try {
            return new XPost(row.getId(), row.getTargetUserId(), row.getGenerationKey(),
                    json.readValue(row.getContentPolicyJson(), XContentPolicy.class),
                    row.getBody(), row.getReviewNote(), row.getContentVersion(), row.getRevision(),
                    XPostStatus.valueOf(row.getStatus()), instant(row.getCreatedAt()), instant(row.getUpdatedAt()),
                    instant(row.getExpiresAt()), row.getReviewer(), instant(row.getReviewedAt()), row.getReviewReason(),
                    row.getAttemptId(), instant(row.getPublishStartedAt()), row.getPostId(), row.getLastError());
        } catch (Exception e) { throw new IllegalStateException("Cannot restore X post"); }
    }

    private static int bound(int limit) { return Math.max(1, Math.min(100, limit)); }
    private static Timestamp time(Instant value) {
        return value == null ? null : Timestamp.from(value.truncatedTo(ChronoUnit.MICROS));
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
