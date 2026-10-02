package com.trade.weibo.domain.model;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class WeiboPostTest {
    private final Instant now = Instant.parse("2026-10-02T01:00:00Z");
    private final WeiboWorkflowPolicy policy = new WeiboWorkflowPolicy(true, true, true, "uid", 280, 5, 3,
            Duration.ofHours(24), Duration.ofHours(6), Duration.ofMinutes(30), Duration.ofMinutes(5));
    private final HotEvent event = new HotEvent("事件", "https://example.test/news/1", "来源提供的事实摘要",
            now.minusSeconds(60), now);

    @Test
    void onlyReviewedUnchangedVersionCanBeClaimed() {
        WeiboPost pending = draft();
        assertThrows(IllegalStateException.class, () -> pending.claim(now));
        WeiboPost approved = pending.review(1, true, "reviewer", "checked", now, now);
        WeiboPost claimed = approved.claim(now);
        assertEquals(WeiboPostStatus.PUBLISHING, claimed.status());
        assertNotNull(claimed.attemptId());
        assertEquals(WeiboPostStatus.PUBLISHED, claimed.finish(WeiboPostStatus.PUBLISHED, "id", null, now).status());
        assertThrows(IllegalArgumentException.class, () -> claimed.finish(WeiboPostStatus.PUBLISHED, null, null, now));
        WeiboPost edited = approved.revise("人工修改的正文", now.plusSeconds(1), 280);
        assertEquals(2, edited.contentVersion());
        assertNull(edited.reviewer());
        assertNull(edited.reviewedAt());
        assertEquals(WeiboPostStatus.PENDING_REVIEW, edited.status());
        assertThrows(IllegalStateException.class,
                () -> edited.review(1, true, "reviewer", null, now.plusSeconds(1), now.plusSeconds(1)));
    }

    @Test
    void rejectedExpiredAndTerminalPostsCannotBeSentOrEdited() {
        WeiboPost pending = draft();
        WeiboPost rejected = pending.review(1, false, "reviewer", "unsupported", now, now);
        assertThrows(IllegalStateException.class, () -> rejected.claim(now));
        Instant expiry = pending.expiresAt();
        assertThrows(IllegalStateException.class, () -> pending.review(1, true, "reviewer", null, expiry, expiry));
        assertEquals(WeiboPostStatus.EXPIRED, pending.expire(expiry).status());
        WeiboPost unknown = pending.review(1, true, "reviewer", null, now, now).claim(now)
                .finish(WeiboPostStatus.UNKNOWN, null, "timeout", now);
        assertThrows(IllegalStateException.class, () -> unknown.claim(now));
        assertThrows(IllegalStateException.class, () -> unknown.revise("重发", now, 280));
    }

    @Test
    void staleEventsFutureReviewsAndOverlongBodiesAreRejected() {
        HotEvent old = new HotEvent("old", "https://example.test/old", "summary", now.minus(Duration.ofDays(2)), now);
        assertThrows(IllegalArgumentException.class, () -> WeiboPost.generating("uid", old, now, policy));
        assertThrows(IllegalStateException.class,
                () -> draft().review(1, true, "reviewer", null, now.plusSeconds(100), now));
        assertThrows(IllegalArgumentException.class,
                () -> draft().revise("字".repeat(281), now, 280));
        assertEquals("😀".repeat(280), draft().revise("😀".repeat(280), now, 280).body());
    }

    @Test
    void sourceIdentityIsStableAcrossHeadlineEditsAndUrlFragments() {
        HotEvent modified = new HotEvent("更新标题", event.sourceUrl() + "#section", "更新摘要", now, now);
        assertEquals(event.key(), modified.key());
        assertThrows(IllegalArgumentException.class,
                () -> new HotEvent("bad", "file:///local", "text", now, now));
    }

    private WeiboPost draft() {
        return WeiboPost.generating("uid", event, now, policy).generated(new GeneratedComment("我的观点", "审核依据"), now, 280);
    }
}
