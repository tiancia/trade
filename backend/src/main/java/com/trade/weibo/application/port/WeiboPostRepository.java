package com.trade.weibo.application.port;

import com.trade.weibo.domain.model.WeiboPost;
import com.trade.weibo.domain.model.WeiboPostHistory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Atomic reservations and CAS updates must persist their audit history in the same transaction. */
public interface WeiboPostRepository {
    boolean reserveGeneration(WeiboPost post, Instant dayStart, int dailyLimit);
    Optional<WeiboPost> find(String id);
    List<WeiboPost> recent(int limit);
    List<WeiboPost> actionable(int limit);
    List<WeiboPostHistory> history(String id);
    boolean save(WeiboPost changed, long expectedRevision);
    boolean claimPublishing(WeiboPost claimed, long expectedRevision, Instant dayStart,
                            int dailyLimit, Duration minInterval);
    boolean finishPublishing(WeiboPost finished, long expectedRevision);
}
