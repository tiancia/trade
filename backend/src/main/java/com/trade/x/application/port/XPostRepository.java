package com.trade.x.application.port;

import com.trade.x.domain.model.XPost;
import com.trade.x.domain.model.XPostHistory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Atomic reservations and CAS updates must persist their audit history in the same transaction. */
public interface XPostRepository {
    boolean reserveGeneration(XPost post, Instant dayStart, int dailyLimit);
    Optional<XPost> find(String id);
    List<XPost> recent(int limit);
    List<XPost> actionable(int limit);
    List<XPostHistory> history(String id);
    boolean save(XPost changed, long expectedRevision);
    boolean claimPublishing(XPost claimed, long expectedRevision, Instant dayStart,
                            int dailyLimit, Duration minInterval);
    boolean finishPublishing(XPost finished, long expectedRevision);
}
