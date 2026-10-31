package com.trade.weibo.application.service;

import com.trade.client.weibo.WeiboHttpException;
import com.trade.weibo.port.HumanReviewGateway;
import com.trade.weibo.domain.model.ReviewDecision;
import com.trade.weibo.domain.model.ReviewRequest;
import com.trade.weibo.port.HotEventSource;
import com.trade.weibo.port.WeiboDraftGenerator;
import com.trade.weibo.port.WeiboPostPublisher;
import com.trade.weibo.port.WeiboPostRepository;
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
    private java.util.function.Supplier<WeiboWorkflowPolicy> policySource;
    private final Clock clock;

    @Autowired
    public WeiboPostService(WeiboPostRepository posts, HotEventSource source, WeiboDraftGenerator generator,
            HumanReviewGateway reviews, WeiboPostPublisher publisher,
            com.trade.weibo.infrastructure.config.WeiboWorkflowProperties settings) {
        this(posts, source, generator, reviews, publisher, settings.policy(), Clock.systemUTC());
        this.policySource = settings::policy;
    }

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
        this.policySource = () -> policy;
        this.clock = clock;
    }

    public Optional<WeiboPost> generate(HotEvent event) { return generate(event, false); }

    private Optional<WeiboPost> generate(HotEvent event, boolean requireReviewDelivery) {
        policySource.get().requireGeneration();
        Instant now = Instant.now(clock);
        WeiboPost reserved = WeiboPost.generating(policySource.get().targetUid(), event, now, policySource.get());
        if (!posts.reserveGeneration(reserved, dayStart(now), policySource.get().dailyGenerationLimit())) return Optional.empty();
        WeiboPost generated;
        try {
            generated = reserved.generated(generator.generate(event, policySource.get().maxBodyChars()),
                    Instant.now(clock), policySource.get().maxBodyChars());
        } catch (RuntimeException e) {
            generated = reserved.generationFailed(Instant.now(clock));
            log.warn("Weibo generation failed: postId={}", reserved.id());
        }
        requireSaved(posts.save(generated, reserved.revision()));
        boolean delivered = submitReview(generated);
        if (requireReviewDelivery && !delivered) throw new IllegalStateException("Weibo review delivery failed");
        return Optional.of(generated);
    }

    /** A failed item does not prevent the remaining events from being processed. */
    public boolean runGeneration() {
        if (!policySource.get().enabled() || !policySource.get().generationEnabled() || policySource.get().targetUid().isBlank()) return true;
        boolean successful = true;
        for (HotEvent event : source.collect()) {
            try {
                Optional<WeiboPost> result = generate(event, true);
                if (result.isPresent() && result.get().status() == WeiboPostStatus.GENERATION_FAILED) successful = false;
            } catch (RuntimeException e) {
                successful = false;
                log.warn("Weibo event skipped: eventKey={}", event.key());
            }
        }
        return successful;
    }

    public List<WeiboPost> recent(int limit) { return posts.recent(limit); }
    public WeiboPost get(String id) {
        return posts.find(id).orElseThrow(() -> new IllegalArgumentException("Weibo post not found"));
    }
    public List<WeiboPostHistory> history(String id) { get(id); return posts.history(id); }

    public WeiboPost revise(String id, long expectedRevision, String body) {
        policySource.get().requireEnabled();
        WeiboPost current = get(id);
        if (current.revision() != expectedRevision) throw new IllegalStateException("Post changed; reload before editing");
        WeiboPost changed = current.revise(body, Instant.now(clock), policySource.get().maxBodyChars());
        requireSaved(posts.save(changed, expectedRevision));
        submitReview(changed);
        return changed;
    }

    /** Internal use case; Telegram decisions reach this only after reviewer/message authentication. */
    public WeiboPost applyReview(ReviewDecision decision) {
        policySource.get().requireEnabled();
        if (!"weibo".equals(decision.module())) throw new IllegalArgumentException("Review belongs to another module");
        WeiboPost current = get(decision.reference());
        // Exact duplicate callbacks are harmless, conflicting callbacks remain rejected.
        if (matchesReview(current, decision)) return current;
        WeiboPost reviewed = current.review(decision.version(), decision.approved(), decision.reviewer(),
                decision.reason(), decision.decidedAt(), Instant.now(clock));
        requireSaved(posts.save(reviewed, current.revision()));
        return reviewed;
    }

    public boolean runReviews() {
        if (!policySource.get().enabled()) return true;
        boolean[] approved = {false};
        reviews.poll(decision -> {
            boolean applied = consumeReview(decision);
            if (applied && decision.approved()) approved[0] = true;
            return applied;
        });
        return !approved[0] || runPublishing();
    }

    private boolean consumeReview(ReviewDecision decision) {
        policySource.get().requireEnabled();
        if (!"weibo".equals(decision.module())) return false;
        WeiboPost current = posts.find(decision.reference()).orElse(null);
        if (current != null && !policySource.get().targetUid().equals(current.targetUid())) return false;
        if (matchesReview(current, decision)) return true;
        if (!canReview(current, decision)) return false;
        try {
            applyReview(decision);
            return true;
        } catch (IllegalStateException conflict) {
            // A concurrent edit/expiry is final; a still eligible CAS failure must be retried.
            WeiboPost latest = posts.find(decision.reference()).orElse(null);
            if (matchesReview(latest, decision)) return true;
            if (!canReview(latest, decision)) return false;
            throw conflict;
        }
    }

    private boolean canReview(WeiboPost post, ReviewDecision decision) {
        Instant now = Instant.now(clock);
        return post != null && post.status() == WeiboPostStatus.PENDING_REVIEW
                && policySource.get().targetUid().equals(post.targetUid())
                && post.contentVersion() == decision.version() && post.live(now)
                && !decision.decidedAt().isBefore(post.updatedAt())
                && !decision.decidedAt().isAfter(now.plusSeconds(30));
    }

    private static boolean matchesReview(WeiboPost post, ReviewDecision decision) {
        if (post == null || post.reviewer() == null || post.reviewedAt() == null
                || post.contentVersion() != decision.version()
                || !post.reviewer().equals(decision.reviewer())
                || !Objects.equals(post.reviewReason(), decision.reason())
                || !post.reviewedAt().equals(decision.decidedAt())) return false;
        return switch (post.status()) {
            case APPROVED, PUBLISHING, PUBLISHED, FAILED, UNKNOWN -> decision.approved();
            case REJECTED -> !decision.approved();
            // Only an approved post retains an audit when the publishing loop expires it.
            case EXPIRED -> decision.approved();
            default -> false;
        };
    }

    public boolean runPublishing() {
        if (!policySource.get().enabled()) return true;
        boolean successful = true;
        for (WeiboPost snapshot : posts.actionable(100)) {
            try { if (!process(snapshot)) successful = false; }
            catch (RuntimeException e) {
                successful = false;
                // No provider payloads in logs. Other posts remain isolated from a failed item.
                log.warn("Weibo post processing failed: postId={}, state={}", snapshot.id(), snapshot.status());
            }
        }
        return successful;
    }

    private boolean process(WeiboPost snapshot) {
        Instant now = Instant.now(clock);
        if (snapshot.status() == WeiboPostStatus.GENERATING) {
            if (!snapshot.updatedAt().plus(policySource.get().claimTimeout()).isAfter(now)) {
                requireSaved(posts.save(snapshot.generationFailed(now), snapshot.revision()));
                return false;
            }
            return true;
        }
        if (snapshot.status() == WeiboPostStatus.PUBLISHING) {
            if (!snapshot.publishStartedAt().plus(policySource.get().claimTimeout()).isAfter(now)) {
                requireSaved(posts.finishPublishing(snapshot.finish(WeiboPostStatus.UNKNOWN, null,
                        "Publishing interrupted; verify on Weibo before taking further action", now), snapshot.revision()));
                return false;
            }
            return true;
        }
        if (!snapshot.live(now)) {
            posts.save(snapshot.expire(now), snapshot.revision());
            return true;
        }
        if (snapshot.status() == WeiboPostStatus.PENDING_REVIEW) return submitReview(snapshot);
        if (!policySource.get().publishingEnabled() || !policySource.get().targetUid().equals(snapshot.targetUid())) return true;
        if (!publisher.credentialsAvailable(snapshot.targetUid())) return false;
        policySource.get().requirePublishing();
        WeiboPost claimed = snapshot.claim(now);
        if (!posts.claimPublishing(claimed, snapshot.revision(), dayStart(now), policySource.get().dailyPublishLimit(),
                policySource.get().publishInterval())) return true;

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
        return finished.status() == WeiboPostStatus.PUBLISHED;
    }

    private boolean submitReview(WeiboPost post) {
        if (post.status() != WeiboPostStatus.PENDING_REVIEW || !policySource.get().targetUid().equals(post.targetUid())
                || !post.live(Instant.now(clock))) return true;
        if (reviews.disabled()) return true;
        try {
            return reviews.submit(new ReviewRequest("weibo", post.id(), post.contentVersion(), post.body(),
                    "目标账号：" + post.targetUid() + "\n事件：" + post.event().title()
                            + "\n来源：" + post.event().sourceUrl() + "\n事件时间：" + post.event().occurredAt()
                            + "\n来源摘要：" + post.event().summary() + "\n审核说明：" + post.reviewNote(), post.expiresAt()));
        } catch (RuntimeException e) { log.warn("Weibo review delivery unavailable: postId={}", post.id()); return false; }
    }

    private static Instant dayStart(Instant now) { return now.truncatedTo(ChronoUnit.DAYS); }
    private static void requireSaved(boolean saved) {
        if (!saved) throw new IllegalStateException("Post changed concurrently; reload its latest state");
    }
}
