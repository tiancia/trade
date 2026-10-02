package com.trade.weibo.application.service;

import com.trade.client.weibo.WeiboHttpException;
import com.trade.telegram.application.port.HumanReviewGateway;
import com.trade.telegram.domain.model.ReviewDecision;
import com.trade.telegram.domain.model.ReviewRequest;
import com.trade.weibo.application.port.HotEventSource;
import com.trade.weibo.application.port.WeiboDraftGenerator;
import com.trade.weibo.application.port.WeiboPostPublisher;
import com.trade.weibo.application.port.WeiboPostRepository;
import com.trade.weibo.domain.exception.WeiboPublishingException;
import com.trade.weibo.domain.model.HotEvent;
import com.trade.weibo.domain.model.WeiboPost;
import com.trade.weibo.domain.model.WeiboPostHistory;
import com.trade.weibo.domain.model.WeiboPostStatus;
import com.trade.weibo.domain.model.WeiboWorkflowPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/** Orchestrates short DB transactions around external calls, never across them. */
@Service
public class WeiboPostService {
    private static final Logger log = LoggerFactory.getLogger(WeiboPostService.class);
    private final WeiboPostRepository posts;
    private final HotEventSource source;
    private final WeiboDraftGenerator generator;
    private final HumanReviewGateway reviews;
    private final WeiboPostPublisher publisher;
    private final WeiboWorkflowPolicy policy;
    private final Clock clock;

    @Autowired
    public WeiboPostService(WeiboPostRepository posts, HotEventSource source, WeiboDraftGenerator generator,
                             HumanReviewGateway reviews, WeiboPostPublisher publisher, WeiboWorkflowPolicy policy) {
        this(posts, source, generator, reviews, publisher, policy, Clock.systemUTC());
    }

    public WeiboPostService(WeiboPostRepository posts, HotEventSource source, WeiboDraftGenerator generator,
                             HumanReviewGateway reviews, WeiboPostPublisher publisher, WeiboWorkflowPolicy policy,
                             Clock clock) {
        this.posts = posts;
        this.source = source;
        this.generator = generator;
        this.reviews = reviews;
        this.publisher = publisher;
        this.policy = policy;
        this.clock = clock;
    }

    public Optional<WeiboPost> generate(HotEvent event) {
        policy.requireGeneration();
        Instant now = Instant.now(clock);
        WeiboPost reserved = WeiboPost.generating(policy.targetUid(), event, now, policy);
        if (!posts.reserveGeneration(reserved, dayStart(now), policy.dailyGenerationLimit())) return Optional.empty();
        WeiboPost generated;
        try {
            generated = reserved.generated(generator.generate(event, policy.maxBodyChars()),
                    Instant.now(clock), policy.maxBodyChars());
        } catch (RuntimeException e) {
            generated = reserved.generationFailed(Instant.now(clock));
            log.warn("Weibo generation failed: postId={}", reserved.id());
        }
        requireSaved(posts.save(generated, reserved.revision()));
        submitReview(generated);
        return Optional.of(generated);
    }

    public void runGeneration() {
        if (!policy.enabled() || !policy.generationEnabled() || policy.targetUid().isBlank()) return;
        for (HotEvent event : source.collect()) {
            try { generate(event); }
            catch (RuntimeException e) {
                log.warn("Weibo event skipped: eventKey={}", event.key());
            }
        }
    }

    public List<WeiboPost> recent(int limit) { return posts.recent(limit); }
    public WeiboPost get(String id) {
        return posts.find(id).orElseThrow(() -> new IllegalArgumentException("Weibo post not found"));
    }
    public List<WeiboPostHistory> history(String id) { get(id); return posts.history(id); }

    public WeiboPost revise(String id, long expectedRevision, String body) {
        policy.requireEnabled();
        WeiboPost current = get(id);
        if (current.revision() != expectedRevision) throw new IllegalStateException("Post changed; reload before editing");
        WeiboPost changed = current.revise(body, Instant.now(clock), policy.maxBodyChars());
        requireSaved(posts.save(changed, expectedRevision));
        submitReview(changed);
        return changed;
    }

    /** Internal use case; no HTTP approval shortcut. Future Telegram adapter calls after authentication. */
    public WeiboPost applyReview(ReviewDecision decision) {
        policy.requireEnabled();
        if (!"weibo".equals(decision.module())) throw new IllegalArgumentException("Review belongs to another module");
        WeiboPost current = get(decision.reference());
        // Exact duplicate callbacks are harmless, conflicting callbacks remain rejected.
        if ((current.status() == WeiboPostStatus.APPROVED || current.status() == WeiboPostStatus.REJECTED)
                && current.contentVersion() == decision.version()
                && current.reviewer().equals(decision.reviewer())
                && Objects.equals(current.reviewReason(), decision.reason())
                && current.reviewedAt().equals(decision.decidedAt())
                && (current.status() == WeiboPostStatus.APPROVED) == decision.approved()) return current;
        WeiboPost reviewed = current.review(decision.version(), decision.approved(), decision.reviewer(),
                decision.reason(), decision.decidedAt(), Instant.now(clock));
        requireSaved(posts.save(reviewed, current.revision()));
        return reviewed;
    }

    public void runPublishing() {
        if (!policy.enabled()) return;
        for (WeiboPost snapshot : posts.actionable(100)) {
            try { process(snapshot); }
            catch (RuntimeException e) {
                // No provider payloads in logs. Other posts remain isolated from a failed item.
                log.warn("Weibo post processing failed: postId={}, state={}", snapshot.id(), snapshot.status());
            }
        }
    }

    private void process(WeiboPost snapshot) {
        Instant now = Instant.now(clock);
        if (snapshot.status() == WeiboPostStatus.GENERATING) {
            if (!snapshot.updatedAt().plus(policy.claimTimeout()).isAfter(now)) {
                posts.save(snapshot.generationFailed(now), snapshot.revision());
            }
            return;
        }
        if (snapshot.status() == WeiboPostStatus.PUBLISHING) {
            if (!snapshot.publishStartedAt().plus(policy.claimTimeout()).isAfter(now)) {
                posts.finishPublishing(snapshot.finish(WeiboPostStatus.UNKNOWN, null,
                        "Publishing interrupted; verify on Weibo before taking further action", now), snapshot.revision());
            }
            return;
        }
        if (!snapshot.live(now)) {
            posts.save(snapshot.expire(now), snapshot.revision());
            return;
        }
        if (snapshot.status() == WeiboPostStatus.PENDING_REVIEW) { submitReview(snapshot); return; }
        if (!policy.publishingEnabled() || !policy.targetUid().equals(snapshot.targetUid())
                || !publisher.credentialsAvailable(snapshot.targetUid())) return;
        policy.requirePublishing();
        WeiboPost claimed = snapshot.claim(now);
        if (!posts.claimPublishing(claimed, snapshot.revision(), dayStart(now), policy.dailyPublishLimit(),
                policy.publishInterval())) return;

        // The durable claim and attempt are committed before sending to the provider.
        WeiboPost finished;
        try {
            String id = publisher.publish(claimed);
            finished = claimed.finish(WeiboPostStatus.PUBLISHED, id, null, Instant.now(clock));
        } catch (WeiboPublishingException e) {
            // The local publisher uses this only for pre-send gates/credential checks.
            finished = claimed.finish(WeiboPostStatus.FAILED, null, "Publishing blocked before send", Instant.now(clock));
        } catch (WeiboHttpException e) {
            WeiboPostStatus outcome = e.statusCode() >= 400 && e.statusCode() < 500 && e.statusCode() != 408
                    ? WeiboPostStatus.FAILED : WeiboPostStatus.UNKNOWN;
            finished = claimed.finish(outcome, null, "Weibo request failed: HTTP " + e.statusCode(), Instant.now(clock));
        } catch (RuntimeException e) {
            finished = claimed.finish(WeiboPostStatus.UNKNOWN, null,
                    "Publishing result is uncertain; verify on Weibo, do not automatically retry", Instant.now(clock));
        }
        // A failed completion transaction leaves a durable PUBLISHING claim, later recovered to UNKNOWN.
        requireSaved(posts.finishPublishing(finished, claimed.revision()));
    }

    private void submitReview(WeiboPost post) {
        if (post.status() != WeiboPostStatus.PENDING_REVIEW || !post.live(Instant.now(clock))) return;
        try {
            reviews.submit(new ReviewRequest("weibo", post.id(), post.contentVersion(), post.body(),
                    "目标账号：" + post.targetUid() + "\n事件：" + post.event().title()
                            + "\n来源：" + post.event().sourceUrl() + "\n事件时间：" + post.event().occurredAt()
                            + "\n来源摘要：" + post.event().summary() + "\n审核说明：" + post.reviewNote(), post.expiresAt()));
        } catch (RuntimeException e) { log.warn("Weibo review delivery unavailable: postId={}", post.id()); }
    }

    private static Instant dayStart(Instant now) { return now.truncatedTo(ChronoUnit.DAYS); }
    private static void requireSaved(boolean saved) {
        if (!saved) throw new IllegalStateException("Post changed concurrently; reload its latest state");
    }
}
