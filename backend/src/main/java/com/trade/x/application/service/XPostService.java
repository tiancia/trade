package com.trade.x.application.service;

import com.trade.client.x.XApiException;
import com.trade.x.application.decision.XPostTextValidator;
import com.trade.x.port.XDraftGenerator;
import com.trade.x.port.XHumanReviewGateway;
import com.trade.x.port.XPostPublisher;
import com.trade.x.port.XPostRepository;
import com.trade.x.domain.exception.XPublishingException;
import com.trade.x.domain.model.GeneratedXPost;
import com.trade.x.domain.model.XContentPolicy;
import com.trade.x.domain.model.XPost;
import com.trade.x.domain.model.XPostHistory;
import com.trade.x.domain.model.XPostStatus;
import com.trade.x.domain.model.XReviewDecision;
import com.trade.x.domain.model.XReviewRequest;
import com.trade.x.domain.model.XWorkflowPolicy;
import com.trade.x.infrastructure.config.XWorkflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** External AI, review and publish calls run between short, committed database operations. */
@Service("xPostService")
public class XPostService {
    private static final Logger log = LoggerFactory.getLogger(XPostService.class);
    private final XPostRepository posts;
    private final XDraftGenerator generator;
    private final XHumanReviewGateway reviews;
    private final XPostPublisher publisher;
    private final XWorkflowPolicy policy;
    private final long generationIntervalMs;
    private final Clock clock;

    @Autowired
    public XPostService(XPostRepository posts, XDraftGenerator generator, XHumanReviewGateway reviews,
            XPostPublisher publisher, XWorkflowPolicy policy, XWorkflowProperties settings) {
        this(posts, generator, reviews, publisher, policy,
                settings.getGenerationFixedDelayMs(), Clock.systemUTC());
    }

    public XPostService(XPostRepository posts, XDraftGenerator generator, XHumanReviewGateway reviews,
            XPostPublisher publisher, XWorkflowPolicy policy, long generationIntervalMs, Clock clock) {
        if (generationIntervalMs < 1000) throw new IllegalArgumentException("Invalid X generation interval");
        this.posts = posts; this.generator = generator; this.reviews = reviews; this.publisher = publisher;
        this.policy = policy; this.generationIntervalMs = generationIntervalMs; this.clock = clock;
    }

    public Optional<XPost> generate() { return generate("manual:" + UUID.randomUUID(), false); }

    private Optional<XPost> generate(String key, boolean requireReviewDelivery) {
        policy.requireGeneration();
        Instant now = now();
        XPost reserved = XPost.generating(policy.targetUserId(), key, now, policy);
        if (!posts.reserveGeneration(reserved, dayStart(now), policy.dailyGenerationLimit())) return Optional.empty();
        XPost generated;
        try {
            List<String> recentBodies = posts.recent(30).stream()
                    .filter(post -> reserved.targetUserId().equals(post.targetUserId()))
                    .map(XPost::body).filter(body -> body != null && !body.isBlank())
                    .distinct().limit(8).toList();
            GeneratedXPost draft = generator.generate(reserved.contentPolicy(), recentBodies);
            String body = XPostTextValidator.normalizeAndValidate(draft.body(), reserved.contentPolicy());
            if (XContentPolicy.repeatsRecentBody(body, recentBodies)) {
                throw new IllegalArgumentException("X draft repeats recent content");
            }
            generated = reserved.generated(new GeneratedXPost(body, draft.reviewNote()), now());
        } catch (RuntimeException failure) {
            generated = reserved.generationFailed(now());
            log.warn("X draft generation failed: postId={}", reserved.id());
        }
        requireSaved(posts.save(generated, reserved.revision()));
        boolean delivered = submitReview(generated);
        if (requireReviewDelivery && !delivered) throw new IllegalStateException("X review delivery failed");
        return Optional.of(generated);
    }

    public boolean runGeneration() {
        if (!policy.enabled() || !policy.generationEnabled()) return true;
        // One durable reservation per UTC interval and account, including multiple app instances.
        try {
            Optional<XPost> result = generate("scheduled:" + Math.floorDiv(now().toEpochMilli(), generationIntervalMs), true);
            return result.isEmpty() || result.get().status() != XPostStatus.GENERATION_FAILED;
        } catch (RuntimeException failure) {
            log.warn("X scheduled generation unavailable");
            return false;
        }
    }

    public List<XPost> recent(int limit) { return posts.recent(limit); }
    public XPost get(String id) {
        return posts.find(id).orElseThrow(() -> new IllegalArgumentException("X post not found"));
    }
    public List<XPostHistory> history(String id) { get(id); return posts.history(id); }

    public XPost revise(String id, long expectedRevision, String body) {
        policy.requireEnabled();
        XPost current = get(id);
        if (!policy.targetUserId().equals(current.targetUserId())) throw new IllegalStateException("X target account changed");
        if (current.revision() != expectedRevision) throw new IllegalStateException("Post changed; reload before editing");
        String validated = XPostTextValidator.normalizeAndValidate(body, current.contentPolicy());
        XPost changed = current.revise(validated, now());
        requireSaved(posts.save(changed, expectedRevision));
        submitReview(changed);
        return changed;
    }

    /** Called only after the Telegram adapter authenticates the reviewer and bound message. */
    public XPost applyReview(XReviewDecision decision) {
        policy.requireEnabled();
        XPost current = get(decision.reference());
        if (!policy.targetUserId().equals(current.targetUserId())) throw new IllegalStateException("X target account changed");
        if (matchesReview(current, decision)) return current;
        XPost changed = current.review(decision.version(), decision.approved(), decision.reviewer(),
                decision.reason(), decision.decidedAt(), now());
        requireSaved(posts.save(changed, current.revision()));
        return changed;
    }

    public boolean runReviews() {
        if (!policy.enabled()) return true;
        boolean[] approved = {false};
        reviews.poll(decision -> {
            boolean applied = consumeReview(decision);
            if (applied && decision.approved()) approved[0] = true;
            return applied;
        });
        return !approved[0] || runPublishing();
    }

    private boolean consumeReview(XReviewDecision decision) {
        XPost current = posts.find(decision.reference()).orElse(null);
        if (current != null && !policy.targetUserId().equals(current.targetUserId())) return false;
        if (matchesReview(current, decision)) return true;
        if (!canReview(current, decision)) return false;
        try { applyReview(decision); return true; }
        catch (IllegalStateException conflict) {
            XPost latest = posts.find(decision.reference()).orElse(null);
            if (matchesReview(latest, decision)) return true;
            if (!canReview(latest, decision)) return false;
            throw conflict;
        }
    }

    private boolean canReview(XPost post, XReviewDecision decision) {
        return post != null && post.status() == XPostStatus.PENDING_REVIEW
                && policy.targetUserId().equals(post.targetUserId()) && post.contentVersion() == decision.version()
                && post.live(now()) && !decision.decidedAt().isBefore(post.updatedAt())
                && !decision.decidedAt().isAfter(now().plusSeconds(30));
    }

    private static boolean matchesReview(XPost post, XReviewDecision decision) {
        if (post == null || post.reviewer() == null || post.reviewedAt() == null
                || post.contentVersion() != decision.version() || !post.reviewer().equals(decision.reviewer())
                || !Objects.equals(post.reviewReason(), decision.reason()) || !post.reviewedAt().equals(decision.decidedAt())) return false;
        return switch (post.status()) {
            case APPROVED, PUBLISHING, PUBLISHED, FAILED, UNKNOWN, EXPIRED -> decision.approved();
            case REJECTED -> !decision.approved();
            default -> false;
        };
    }

    public boolean runPublishing() {
        if (!policy.enabled()) return true;
        boolean successful = true;
        for (XPost snapshot : posts.actionable(100)) {
            try { if (!process(snapshot)) successful = false; }
            catch (RuntimeException failure) {
                successful = false;
                log.warn("X post processing failed: postId={}, state={}", snapshot.id(), snapshot.status());
            }
        }
        return successful;
    }

    private boolean process(XPost snapshot) {
        Instant now = now();
        if (snapshot.status() == XPostStatus.GENERATING) {
            if (!snapshot.updatedAt().plus(policy.claimTimeout()).isAfter(now)) {
                requireSaved(posts.save(snapshot.generationFailed(now), snapshot.revision()));
                return false;
            }
            return true;
        }
        if (snapshot.status() == XPostStatus.PUBLISHING) {
            if (!snapshot.publishStartedAt().plus(policy.claimTimeout()).isAfter(now)) {
                XPost interrupted = snapshot.finish(XPostStatus.UNKNOWN, null,
                        "Publishing interrupted; verify on X before taking further action", now);
                requireSaved(posts.finishPublishing(interrupted, snapshot.revision()));
                logPublishingFailure(interrupted);
                return false;
            }
            return true;
        }
        if (!snapshot.live(now)) { posts.save(snapshot.expire(now), snapshot.revision()); return true; }
        if (snapshot.status() == XPostStatus.PENDING_REVIEW) return submitReview(snapshot);
        if (!policy.publishingEnabled()) {
            recordWaiting(snapshot, "Waiting to publish: X workflow publishing is disabled", now);
            return true;
        }
        if (!policy.targetUserId().equals(snapshot.targetUserId())) {
            recordWaiting(snapshot, "Waiting to publish: X target account differs from this draft", now);
            return true;
        }
        if (!publisher.credentialsAvailable(snapshot.targetUserId())) {
            String reason = publisher.unavailableReason(snapshot.targetUserId());
            recordWaiting(snapshot, "Waiting to publish: " + (reason == null
                    ? "Publisher gates or credentials are unavailable" : reason), now);
            return false;
        }
        policy.requirePublishing();
        String validated = XPostTextValidator.normalizeAndValidate(snapshot.body(), snapshot.contentPolicy());
        if (!validated.equals(snapshot.body())) throw new IllegalStateException("Reviewed X text is not canonical");
        XPost claimed = snapshot.claim(now);
        if (!posts.claimPublishing(claimed, snapshot.revision(), dayStart(now), policy.dailyPublishLimit(), policy.publishInterval())) {
            // A failed CAS is also reported as false: never overwrite a newer review or publishing claim.
            posts.find(snapshot.id()).filter(latest -> latest.revision() == snapshot.revision()
                    && latest.status() == XPostStatus.APPROVED).ifPresent(latest -> recordWaiting(latest,
                            "Waiting to publish: local daily attempt limit or minimum interval", now));
            return true;
        }
        XPost finished;
        try {
            finished = claimed.finish(XPostStatus.PUBLISHED, publisher.publish(claimed), null, now());
        } catch (XPublishingException blocked) {
            String reason = blocked.statusCode() == null ? "Publishing blocked before send"
                    : "Publishing blocked before send: X preflight HTTP " + blocked.statusCode();
            finished = claimed.finish(XPostStatus.FAILED, null, reason, now());
        } catch (XApiException rejected) {
            XPostStatus outcome = rejected.statusCode() >= 400 && rejected.statusCode() < 500 && rejected.statusCode() != 408
                    ? XPostStatus.FAILED : XPostStatus.UNKNOWN;
            finished = claimed.finish(outcome, null, "X request failed: HTTP " + rejected.statusCode(), now());
        } catch (RuntimeException uncertain) {
            finished = claimed.finish(XPostStatus.UNKNOWN, null,
                    "Publishing result uncertain; verify on X, do not automatically retry", now());
        }
        requireSaved(posts.finishPublishing(finished, claimed.revision()));
        if (finished.status() != XPostStatus.PUBLISHED) logPublishingFailure(finished);
        return finished.status() == XPostStatus.PUBLISHED;
    }

    private void recordWaiting(XPost snapshot, String reason, Instant now) {
        XPost waiting = snapshot.waitingToPublish(reason, now);
        if (waiting != snapshot && posts.save(waiting, snapshot.revision())) {
            log.warn("X publishing waiting: postId={}, state={}, reason={}", waiting.id(), waiting.status(), waiting.lastError());
        }
    }

    private static void logPublishingFailure(XPost post) {
        log.warn("X publishing unsuccessful: postId={}, state={}, reason={}", post.id(), post.status(), post.lastError());
    }

    private boolean submitReview(XPost post) {
        if (post.status() != XPostStatus.PENDING_REVIEW || !policy.targetUserId().equals(post.targetUserId()) || !post.live(now())) return true;
        if (reviews.disabled()) return true;
        try {
            var content = post.contentPolicy();
            String noteLabel = post.contentVersion() > 1
                    ? "正文已人工修改；原始 AI 说明不代表当前版本，请重新判断："
                    : "创作说明（AI 自述，需人工判断）：";
            String formatReview = content.format() == XContentPolicy.Format.CHAT_DIALOGUE
                    ? "短对话审核：是否只有 2–6 句，每句独占一行并由成对双引号包裹；"
                        + "是否像自然的聊天来回，让情绪从回应、潜台词和未说出口的部分产生；"
                        + "是否混入角色名、标题、旁白、解释、总结或话术教程。"
                        + "本稿是 AI 虚构对话，不得冒充真实聊天截图、亲历或读者投稿。"
                    : "自由文本审核：按草稿冻结的方向、语气和额外规则判断风格；"
                        + "形式与换行是否有效，是否与近期内容雷同；虚构内容是否诚实呈现，避免冒充真实经历或事实依据。";
            return reviews.submit(new XReviewRequest(post.id(), post.contentVersion(), post.targetUserId(), post.body(),
                    noteLabel + (post.reviewNote() == null ? "无原始说明" : post.reviewNote())
                    + "\n创作说明仅用于审核，不随正文发布。"
                    + "\n审核重点：正文是否按冻结语言自然表达、符合冻结内容方向；" + formatReview
                    + "涉及约会、欲望或亲密关系时确认人物均为成年人且关系自愿，表达不露骨且不以性唤起为目的；"
                    + "不诊断读者或他人，不冒充心理或性健康专家，不伪造医疗、心理或研究依据；"
                    + "不承诺挽回，不提供操纵他人的话术或技巧，不保证涨粉或收益。"
                    + "\n内容方向：" + content.direction() + "\n语言：" + content.language() + "\n语气：" + content.tone()
                    + "\n冻结格式：" + content.format()
                    + "\n字数范围：" + content.minChars() + "–" + content.maxChars()
                    + "\n额外规则：" + content.instructions(), post.expiresAt()));
        } catch (RuntimeException failure) { log.warn("X review delivery unavailable: postId={}", post.id()); return false; }
    }

    private Instant now() { return Instant.now(clock).truncatedTo(ChronoUnit.MICROS); }
    private static Instant dayStart(Instant now) { return now.truncatedTo(ChronoUnit.DAYS); }
    private static void requireSaved(boolean saved) {
        if (!saved) throw new IllegalStateException("Post changed concurrently; reload its latest state");
    }
}
