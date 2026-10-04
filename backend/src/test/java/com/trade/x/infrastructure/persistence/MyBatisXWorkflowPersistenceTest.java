package com.trade.x.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.x.application.port.XPostRepository;
import com.trade.x.application.port.XReviewDeliveryStore;
import com.trade.x.domain.model.GeneratedXPost;
import com.trade.x.domain.model.XContentPolicy;
import com.trade.x.domain.model.XPost;
import com.trade.x.domain.model.XPostStatus;
import com.trade.x.domain.model.XReviewDelivery;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes production X DDL and MyBatis statements against an isolated, credential-free H2 database. */
@SpringJUnitConfig(MyBatisXWorkflowPersistenceTest.Config.class)
class MyBatisXWorkflowPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-10-03T02:00:00.123456Z");
    private static final Instant DAY = NOW.truncatedTo(ChronoUnit.DAYS);
    private static final long BOT = 4503599627370495L;
    private static final long CHAT = -1001234567890L;
    private static final XContentPolicy CONTENT = new XContentPolicy("engineering", "en", "plain",
            "Explain practical lessons; avoid hype 🌱", 1, 280);
    @Autowired private XPostRepository posts;
    @Autowired private XReviewDeliveryStore deliveries;
    @Autowired private XPostMapper postMapper;
    @Autowired private XReviewDeliveryMapper deliveryMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void schema() throws Exception {
        for (String table : new String[]{"x_review_delivery", "x_review_polling", "x_publish_attempt",
                "x_post_history", "x_post", "x_account_gate"}) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        String sql = resource("db/schema/x/schema.sql")
                .replaceAll("(?m)^--.*$", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }

    @Test
    void policySnapshotGenerationIdentityAndAuditSurviveRepositoryRecreation() {
        XPost initial = initial("slot-1");
        assertTrue(posts.reserveGeneration(initial, DAY, 5));
        assertEquals(initial, new MyBatisXPostRepository(postMapper).find(initial.id()).orElseThrow());
        assertEquals(CONTENT, posts.find(initial.id()).orElseThrow().contentPolicy());
        assertFalse(posts.reserveGeneration(initial("slot-1"), DAY, 5));
        assertTrue(posts.reserveGeneration(initial("43", "slot-1"), DAY, 5));
        XPost pending = initial.generated(new GeneratedXPost("Keep the whole body 🌱", "Check the source"), NOW);
        assertTrue(posts.save(pending, initial.revision()));
        XPost approved = pending.review(1, true, "reviewer", "checked", NOW, NOW);
        assertTrue(posts.save(approved, pending.revision()));
        assertFalse(posts.save(pending.revise("Attempted stale edit", NOW), pending.revision()));
        XPost claimed = approved.claim(NOW);
        assertTrue(posts.claimPublishing(claimed, approved.revision(), DAY, 3, Duration.ofMinutes(30)));
        assertFalse(posts.claimPublishing(approved.claim(NOW), approved.revision(), DAY, 3, Duration.ofMinutes(30)));
        XPost finished = claimed.finish(XPostStatus.PUBLISHED, "1234567890123456789", null, NOW);
        assertTrue(posts.finishPublishing(finished, claimed.revision()));
        assertEquals(finished, new MyBatisXPostRepository(postMapper).find(initial.id()).orElseThrow());
        assertEquals(CONTENT, posts.find(initial.id()).orElseThrow().contentPolicy());
        assertEquals(5, posts.history(initial.id()).size());
        var audit = posts.history(initial.id()).getFirst();
        assertEquals(XPostStatus.PUBLISHED, audit.status());
        assertEquals(finished.body(), audit.body());
        assertEquals(finished.reviewer(), audit.reviewer());
        assertEquals("checked", audit.reviewReason());
        assertEquals(claimed.attemptId(), audit.attemptId());
        assertEquals("1234567890123456789", audit.postId());
        assertEquals(NOW, audit.updatedAt());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM x_publish_attempt", String.class));
        assertEquals("1234567890123456789", jdbc.queryForObject("SELECT external_post_id FROM x_publish_attempt", String.class));
    }

    @Test
    void dailyQuotasAndPublishIntervalIncludeUncertainAttemptsAndRemainAccountScoped() {
        XPost first = approve(initial("slot-1"));
        XPost claimed = first.claim(NOW);
        assertTrue(posts.claimPublishing(claimed, first.revision(), DAY, 3, Duration.ofMinutes(30)));
        assertTrue(posts.finishPublishing(claimed.finish(XPostStatus.UNKNOWN, null, "transport uncertain", NOW), claimed.revision()));
        XPost second = approve(initial("slot-2"));
        assertFalse(posts.claimPublishing(second.claim(NOW.plusSeconds(10)), second.revision(), DAY, 3, Duration.ofMinutes(30)));
        assertFalse(posts.claimPublishing(second.claim(NOW.plusSeconds(1801)), second.revision(), DAY, 1, Duration.ofMinutes(30)));
        assertTrue(posts.claimPublishing(second.claim(NOW.plusSeconds(1801)), second.revision(), DAY, 3, Duration.ofMinutes(30)));
        assertFalse(posts.reserveGeneration(initial("slot-3"), DAY, 2));
        assertTrue(posts.reserveGeneration(initial("43", "slot-3"), DAY, 2));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM x_publish_attempt", Integer.class));
    }

    @Test
    void concurrentGenerationCannotExceedOneReservationOrDuplicateGenerationKey() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return posts.reserveGeneration(initial("first"), DAY, 1); });
            var second = executor.submit(() -> { start.await(); return posts.reserveGeneration(initial("second"), DAY, 1); });
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_post", Integer.class));
        CountDownLatch duplicateStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { duplicateStart.await(); return posts.reserveGeneration(initial("same-key"), DAY, 10); });
            var second = executor.submit(() -> { duplicateStart.await(); return posts.reserveGeneration(initial("same-key"), DAY, 10); });
            duplicateStart.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_post WHERE generation_key='same-key'", Integer.class));
    }

    @Test
    void concurrentClaimsCreateOnlyOneDurablePublishAttempt() throws Exception {
        XPost approved = approve(initial("slot-1"));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return posts.claimPublishing(approved.claim(NOW), approved.revision(),
                    DAY, 3, Duration.ofMinutes(30)); });
            var second = executor.submit(() -> { start.await(); return posts.claimPublishing(approved.claim(NOW), approved.revision(),
                    DAY, 3, Duration.ofMinutes(30)); });
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_publish_attempt", Integer.class));
        assertEquals(XPostStatus.PUBLISHING, posts.find(approved.id()).orElseThrow().status());
    }

    @Test
    void auditFailureRollsBackCasTransitionAndPreservesOriginalDraft() {
        XPost initial = initial("slot-1");
        assertTrue(posts.reserveGeneration(initial, DAY, 5));
        jdbc.update("INSERT INTO x_post_history (post_key, revision, content_version, status, updated_at) VALUES (?,1,1,'GENERATING',?)",
                initial.id(), Timestamp.from(NOW));
        assertThrows(RuntimeException.class,
                () -> posts.save(initial.generated(new GeneratedXPost("Whole body", "Check facts"), NOW), initial.revision()));
        assertEquals(initial, posts.find(initial.id()).orElseThrow());
    }

    @Test
    void sentReviewFirstDecisionAndCompletionRemainBoundToBotAndVersion() {
        XPost draft = initial("slot-1");
        assertTrue(posts.reserveGeneration(draft, DAY, 5));
        XReviewDelivery delivery = delivery("delivery-1", draft.id());
        assertTrue(deliveries.reserve(delivery));
        assertFalse(deliveries.reserve(delivery("duplicate-version", draft.id())));
        assertFalse(deliveries.markSent(BOT + 1, delivery.id(), 9));
        assertFalse(deliveries.recordDecision(BOT, delivery.id(), "too-early", true, "reviewer", NOW));
        assertTrue(deliveries.markSent(BOT, delivery.id(), 4503599627370494L));
        assertFalse(deliveries.markSent(BOT, delivery.id(), 10));
        deliveries.markUnknown(BOT, delivery.id());
        assertEquals("SENT", deliveries.find(BOT, delivery.id()).orElseThrow().status());
        Instant nanoseconds = NOW.plusSeconds(1).plusNanos(789);
        assertTrue(deliveries.recordDecision(BOT, delivery.id(), "callback-1", true, "telegram:42", nanoseconds));
        assertFalse(deliveries.recordDecision(BOT, delivery.id(), "callback-2", false, "telegram:99", NOW));
        XReviewDelivery stored = new MyBatisXReviewDeliveryStore(deliveryMapper).find(BOT, delivery.id()).orElseThrow();
        assertEquals(CHAT, stored.chatId());
        assertEquals(4503599627370494L, stored.messageId());
        assertEquals("callback-1", stored.callbackId());
        assertTrue(stored.approved());
        assertEquals(nanoseconds.truncatedTo(ChronoUnit.MICROS), stored.decidedAt());
        assertEquals("PENDING", stored.decisionStatus());
        assertTrue(deliveries.find(BOT + 1, delivery.id()).isEmpty());
        assertEquals(stored, deliveries.findForPost(BOT, draft.id(), 1).orElseThrow());
        deliveries.finishDecision(BOT, delivery.id(), true);
        deliveries.finishDecision(BOT, delivery.id(), false);
        assertEquals("APPLIED", deliveries.find(BOT, delivery.id()).orElseThrow().decisionStatus());
    }

    @Test
    void concurrentReviewReservationsAndOpposingDecisionsPersistOnlyOneWinner() throws Exception {
        XPost draft = initial("slot-1");
        assertTrue(posts.reserveGeneration(draft, DAY, 5));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return deliveries.reserve(delivery("first", draft.id())); });
            var second = executor.submit(() -> { start.await(); return deliveries.reserve(delivery("second", draft.id())); });
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        XReviewDelivery sent = deliveries.findForPost(BOT, draft.id(), 1).orElseThrow();
        assertTrue(deliveries.markSent(BOT, sent.id(), 10));
        CountDownLatch decisionStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { decisionStart.await();
                return deliveries.recordDecision(BOT, sent.id(), "approve", true, "reviewer-a", NOW); });
            var second = executor.submit(() -> { decisionStart.await();
                return deliveries.recordDecision(BOT, sent.id(), "reject", false, "reviewer-b", NOW); });
            decisionStart.countDown();
            boolean approvedWon = first.get(10, TimeUnit.SECONDS);
            assertNotEquals(approvedWon, second.get(10, TimeUnit.SECONDS));
            assertEquals(approvedWon, deliveries.find(BOT, sent.id()).orElseThrow().approved());
        }
        deliveries.finishDecision(BOT, sent.id(), false);
        deliveries.finishDecision(BOT, sent.id(), true);
        assertEquals("INVALID", deliveries.find(BOT, sent.id()).orElseThrow().decisionStatus());
    }

    @Test
    void uncertainSendsCannotBecomeSentOrReceiveDecisionsAndForeignKeyErrorsAreVisible() {
        XPost draft = initial("slot-1");
        assertTrue(posts.reserveGeneration(draft, DAY, 5));
        assertTrue(deliveries.reserve(delivery("unknown", draft.id())));
        deliveries.markUnknown(BOT, "unknown");
        assertFalse(deliveries.markSent(BOT, "unknown", 10));
        assertFalse(deliveries.recordDecision(BOT, "unknown", "callback", true, "reviewer", NOW));
        assertEquals("UNKNOWN", deliveries.find(BOT, "unknown").orElseThrow().status());
        assertThrows(DataIntegrityViolationException.class, () -> deliveries.reserve(delivery("missing", "absent-post")));
    }

    @Test
    void concurrentPollersAcquireOnlyOneDatabaseLeasePerBot() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return deliveries.acquirePolling(BOT, "first", NOW, NOW.plusSeconds(30)); });
            var second = executor.submit(() -> { start.await(); return deliveries.acquirePolling(BOT, "second", NOW, NOW.plusSeconds(30)); });
            start.countDown();
            var firstLease = first.get(10, TimeUnit.SECONDS);
            var secondLease = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstLease.isPresent(), secondLease.isPresent());
            assertEquals(0L, firstLease.isPresent() ? firstLease.getAsLong() : secondLease.getAsLong());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_review_polling", Integer.class));
        assertEquals(0L, deliveries.acquirePolling(BOT + 1, "independent", NOW, NOW.plusSeconds(30)).orElseThrow());
    }

    @Test
    void durableCursorCannotRegressAndExpiredOwnersCannotRenewAdvanceOrReleaseNewLease() {
        assertEquals(0L, deliveries.acquirePolling(BOT, "old", NOW, NOW.plusSeconds(20)).orElseThrow());
        assertTrue(deliveries.acquirePolling(BOT, "other", NOW, NOW.plusSeconds(30)).isEmpty());
        assertFalse(deliveries.renewPolling(BOT, "other", NOW, NOW.plusSeconds(30)));
        assertTrue(deliveries.renewPolling(BOT, "old", NOW.plusSeconds(5), NOW.plusSeconds(30)));
        assertTrue(deliveries.advancePolling(BOT, "old", 62L, NOW.plusSeconds(10), NOW.plusSeconds(40)));
        assertFalse(deliveries.advancePolling(BOT, "old", 61L, NOW.plusSeconds(11), NOW.plusSeconds(40)));
        MyBatisXReviewDeliveryStore restarted = new MyBatisXReviewDeliveryStore(deliveryMapper);
        assertEquals(62L, restarted.acquirePolling(BOT, "new", NOW.plusSeconds(40), NOW.plusSeconds(70)).orElseThrow());
        assertFalse(deliveries.advancePolling(BOT, "old", 100L, NOW.plusSeconds(41), NOW.plusSeconds(70)));
        assertFalse(deliveries.renewPolling(BOT, "old", NOW.plusSeconds(41), NOW.plusSeconds(70)));
        deliveries.releasePolling(BOT, "old");
        assertTrue(deliveries.acquirePolling(BOT, "intruder", NOW.plusSeconds(42), NOW.plusSeconds(70)).isEmpty());
        assertFalse(restarted.advancePolling(BOT, "new", 100L, NOW.plusSeconds(70), NOW.plusSeconds(90)));
        restarted.releasePolling(BOT, "new");
        assertEquals(62L, deliveries.acquirePolling(BOT, "third", NOW.plusSeconds(71), NOW.plusSeconds(90)).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> deliveries.advancePolling(BOT, "third", -1L, NOW.plusSeconds(72), NOW.plusSeconds(90)));
        assertThrows(IllegalArgumentException.class,
                () -> deliveries.acquirePolling(BOT + 1, "bad-deadline", NOW, NOW));
    }

    @Test
    void newDatabaseBaselineHasGeneratedNumericPrimaryKeysWithoutReplacingBusinessKeys() throws Exception {
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='public'"
                + " AND TABLE_NAME LIKE 'x_%'", Integer.class));
        assertNumericPrimaryKeys();
        XPost first = approve(initial("slot-1"));
        XPost second = approve(initial("43", "slot-2"));
        for (XPost approved : new XPost[]{first, second}) {
            assertTrue(posts.claimPublishing(approved.claim(NOW), approved.revision(), DAY, 3, Duration.ofMinutes(30)));
            assertTrue(deliveries.reserve(delivery(UUID.randomUUID().toString(), approved.id())));
        }
        assertEquals(0L, deliveries.acquirePolling(BOT, "first", NOW, NOW.plusSeconds(30)).orElseThrow());
        assertEquals(0L, deliveries.acquirePolling(BOT + 1, "second", NOW, NOW.plusSeconds(30)).orElseThrow());
        assertFalse(posts.reserveGeneration(initial("slot-1"), DAY, 10));
        assertFalse(deliveries.reserve(delivery("duplicate-version", first.id())));
        assertEquals(first.id(), jdbc.queryForObject("SELECT post_key FROM x_post WHERE post_key=?", String.class, first.id()));
        assertEquals(first.id(), posts.find(first.id()).orElseThrow().id());
        assertGeneratedIdsInEveryTable();
    }

    @Test
    void manualUpgradePreservesExistingUuidsAssociationsDecisionsAndCursorThenAllowsNewGeneratedIds() throws Exception {
        for (String table : new String[]{"x_review_delivery", "x_review_polling", "x_publish_attempt",
                "x_post_history", "x_post", "x_account_gate"}) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        executeSchema(resource("db/upgrade/x/migration_add_x_workflow.sql"));
        XPost approved = initial("legacy-failed-slot").generated(new GeneratedXPost("A legacy body", "Check sources"), NOW)
                .review(1, true, "legacy-reviewer", "checked", NOW, NOW);
        XPost oldClaim = approved.claim(NOW);
        XPost failed = oldClaim.finish(XPostStatus.FAILED, null, "Rejected before publication", NOW);
        XPost draft = initial("legacy-slot").generated(new GeneratedXPost("An editable legacy draft", "Check sources"), NOW)
                .revise("The revised legacy body", NOW);
        String deliveryKey = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO x_account_gate (user_id) VALUES (?)", draft.targetUserId());
        for (XPost existing : new XPost[]{draft, failed}) {
            jdbc.update("INSERT INTO x_post (id,target_user_id,generation_key,content_policy_json,body,review_note,"
                            + "content_version,revision,status,created_at,updated_at,expires_at,reviewer,reviewed_at,review_reason,"
                            + "attempt_id,publish_started_at,external_post_id,last_error) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    existing.id(), existing.targetUserId(), existing.generationKey(),
                    new ObjectMapper().findAndRegisterModules().writeValueAsString(existing.contentPolicy()), existing.body(),
                    existing.reviewNote(), existing.contentVersion(), existing.revision(), existing.status().name(),
                    Timestamp.from(existing.createdAt()), Timestamp.from(existing.updatedAt()), Timestamp.from(existing.expiresAt()),
                    existing.reviewer(), existing.reviewedAt() == null ? null : Timestamp.from(existing.reviewedAt()), existing.reviewReason(),
                    existing.attemptId(), existing.publishStartedAt() == null ? null : Timestamp.from(existing.publishStartedAt()),
                    existing.postId(), existing.lastError());
            jdbc.update("INSERT INTO x_post_history (id,revision,content_version,status,body,reviewer,review_reason,"
                            + "attempt_id,external_post_id,last_error,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    existing.id(), existing.revision(), existing.contentVersion(), existing.status().name(), existing.body(),
                    existing.reviewer(), existing.reviewReason(), existing.attemptId(), existing.postId(), existing.lastError(), Timestamp.from(NOW));
        }
        jdbc.update("INSERT INTO x_publish_attempt (attempt_id,post_id,target_user_id,content_version,status,started_at,completed_at,last_error)"
                        + " VALUES (?,?,?,1,'FAILED',?,?,?)", oldClaim.attemptId(), failed.id(), failed.targetUserId(),
                Timestamp.from(NOW), Timestamp.from(NOW), failed.lastError());
        jdbc.update("INSERT INTO x_review_delivery (id,bot_id,post_id,content_version,chat_id,message_id,status,expires_at,"
                        + "callback_id,approved,reviewer,decided_at,decision_status) VALUES (?,?,?,?,?,90,'SENT',?,?,?,?,?,'APPLIED')",
                deliveryKey, BOT, failed.id(), 1, CHAT, Timestamp.from(failed.expiresAt()),
                "legacy-callback", true, "legacy-reviewer", Timestamp.from(NOW));
        jdbc.update("INSERT INTO x_review_polling (bot_id,next_offset,lease_owner,lease_until) VALUES (?,123,'legacy-owner',?)",
                BOT, Timestamp.from(NOW.plusSeconds(30)));

        executeManualUpgradeOnH2(resource("db/upgrade/x/migration_add_auto_increment_primary_keys.sql"));

        assertNumericPrimaryKeys();
        assertEquals(draft, posts.find(draft.id()).orElseThrow());
        assertEquals(failed, posts.find(failed.id()).orElseThrow());
        assertEquals(1, posts.history(draft.id()).size());
        assertEquals(draft.revision(), posts.history(draft.id()).getFirst().revision());
        assertEquals(draft.body(), posts.history(draft.id()).getFirst().body());
        assertEquals(oldClaim.attemptId(), jdbc.queryForObject("SELECT attempt_id FROM x_publish_attempt", String.class));
        XReviewDelivery reviewed = deliveries.find(BOT, deliveryKey).orElseThrow();
        assertEquals(failed.id(), reviewed.postId());
        assertEquals("legacy-callback", reviewed.callbackId());
        assertEquals("APPLIED", reviewed.decisionStatus());
        assertTrue(reviewed.approved());
        assertFalse(posts.reserveGeneration(initial("legacy-slot"), DAY, 10));
        assertFalse(deliveries.reserve(delivery("duplicate-version", failed.id())));
        assertTrue(deliveries.acquirePolling(BOT, "intruder", NOW, NOW.plusSeconds(30)).isEmpty());
        assertTrue(deliveries.advancePolling(BOT, "legacy-owner", 124, NOW, NOW.plusSeconds(30)));
        deliveries.releasePolling(BOT, "legacy-owner");
        assertEquals(124L, deliveries.acquirePolling(BOT, "new-owner", NOW, NOW.plusSeconds(30)).orElseThrow());

        XReviewDelivery newReview = new XReviewDelivery(UUID.randomUUID().toString(), BOT, draft.id(), draft.contentVersion(),
                CHAT, null, "SENDING", draft.expiresAt(), null, null, null, null, null);
        assertTrue(deliveries.reserve(newReview));
        assertTrue(deliveries.markSent(BOT, newReview.id(), 91));
        assertTrue(deliveries.recordDecision(BOT, newReview.id(), "new-callback", true, "new-reviewer", NOW));
        deliveries.finishDecision(BOT, newReview.id(), true);
        XPost newlyApproved = draft.review(draft.contentVersion(), true, "new-reviewer", "checked", NOW, NOW);
        assertTrue(posts.save(newlyApproved, draft.revision()));
        XPost claimed = newlyApproved.claim(NOW.plusSeconds(1801));
        assertTrue(posts.claimPublishing(claimed, newlyApproved.revision(), DAY, 3, Duration.ofMinutes(30)));
        assertTrue(posts.finishPublishing(claimed.finish(XPostStatus.PUBLISHED, "999", null, NOW.plusSeconds(1801)), claimed.revision()));
        assertTrue(posts.reserveGeneration(initial("43", "new-slot"), DAY, 10));
        assertEquals(0L, deliveries.acquirePolling(BOT + 1, "independent", NOW, NOW.plusSeconds(30)).orElseThrow());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM x_publish_attempt", Integer.class));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM x_publish_attempt WHERE attempt_id=?", String.class, oldClaim.attemptId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> deliveries.reserve(delivery(UUID.randomUUID().toString(), "missing-post")));
        assertGeneratedIdsInEveryTable();
    }

    private void assertNumericPrimaryKeys() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            for (String table : new String[]{"x_post", "x_post_history", "x_account_gate", "x_publish_attempt",
                    "x_review_delivery", "x_review_polling"}) {
                try (var columns = connection.getMetaData().getColumns(null, "public", table, "id")) {
                    assertTrue(columns.next(), table);
                    assertEquals(Types.BIGINT, columns.getInt("DATA_TYPE"), table);
                    assertEquals("YES", columns.getString("IS_AUTOINCREMENT"), table);
                }
                try (var primaryKeys = connection.getMetaData().getPrimaryKeys(null, "public", table)) {
                    assertTrue(primaryKeys.next(), table);
                    assertEquals("id", primaryKeys.getString("COLUMN_NAME"), table);
                    assertFalse(primaryKeys.next(), table);
                }
            }
        }
    }

    private void assertGeneratedIdsInEveryTable() {
        for (String table : new String[]{"x_post", "x_post_history", "x_account_gate", "x_publish_attempt",
                "x_review_delivery", "x_review_polling"}) {
            assertTrue(jdbc.queryForObject("SELECT MIN(id) FROM " + table, Long.class) > 0, table);
            assertTrue(jdbc.queryForObject("SELECT MAX(id) FROM " + table, Long.class)
                    > jdbc.queryForObject("SELECT MIN(id) FROM " + table, Long.class), table);
            assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class),
                    jdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM " + table, Integer.class), table);
        }
    }

    private void executeSchema(String sql) {
        sql = sql.replaceAll("(?m)^--.*$", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }

    /** Same migration operations; adapt only MySQL ALTER syntax unsupported by the offline H2 engine. */
    private void executeManualUpgradeOnH2(String sql) {
        var primaryKeysDropped = new HashSet<String>();
        for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (statement.isBlank()) continue;
            String[] parts = statement.strip().split("\\s+", 4);
            assertEquals("ALTER", parts[0]);
            assertEquals("TABLE", parts[1]);
            String table = parts[2];
            for (String operation : parts[3].split(",\\s*(?![^()]*\\))")) {
                // Column renames execute first, before either the business UNIQUE or new numeric id.
                // H2 reuses the primary index for a new UNIQUE constraint and cannot then drop it.
                // Restore the identical business uniqueness immediately after dropping the old PK.
                if (operation.strip().startsWith("ADD UNIQUE KEY") && primaryKeysDropped.add(table)) {
                    jdbc.execute("ALTER TABLE " + table + " DROP PRIMARY KEY");
                }
                if (operation.strip().equals("DROP PRIMARY KEY")) {
                    assertTrue(primaryKeysDropped.contains(table), "Old primary key was dropped before the business UNIQUE: " + table);
                    continue;
                }
                String h2 = operation.strip().replace("DROP FOREIGN KEY", "DROP CONSTRAINT")
                        .replace("ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST", "ADD COLUMN id BIGINT GENERATED BY DEFAULT AS IDENTITY")
                        .replaceAll("ADD UNIQUE KEY (\\w+) (\\(.+\\))", "ADD CONSTRAINT $1 UNIQUE $2")
                        .replaceAll("CHANGE COLUMN id (\\w+) varchar\\(36\\) NOT NULL", "RENAME COLUMN id TO $1");
                jdbc.execute("ALTER TABLE " + table + " " + h2);
            }
        }
    }

    private XPost approve(XPost initial) {
        assertTrue(posts.reserveGeneration(initial, DAY, 10));
        XPost pending = initial.generated(new GeneratedXPost("A clear, complete post", "Check its factual basis"), NOW);
        assertTrue(posts.save(pending, initial.revision()));
        XPost approved = pending.review(1, true, "reviewer", "checked", NOW, NOW);
        assertTrue(posts.save(approved, pending.revision()));
        return approved;
    }

    private static XPost initial(String key) { return initial("42", key); }

    private static XPost initial(String userId, String key) {
        return new XPost(UUID.randomUUID().toString(), userId, key, CONTENT, null, null, 1, 0,
                XPostStatus.GENERATING, NOW, NOW, NOW.plusSeconds(3600), null, null, null, null, null, null, null);
    }

    private static XReviewDelivery delivery(String id, String postId) {
        return new XReviewDelivery(id, BOT, postId, 1, CHAT, null, "SENDING", NOW.plusSeconds(3600),
                null, null, null, null, null);
    }

    private static String resource(String path) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:x_workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new ClassPathResource("mapper/x/XPostMapper.xml"),
                    new ClassPathResource("mapper/x/XReviewDeliveryMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<XPostMapper> postMapper(SqlSessionFactory sessions) {
            MapperFactoryBean<XPostMapper> factory = new MapperFactoryBean<>(XPostMapper.class);
            factory.setSqlSessionFactory(sessions);
            return factory;
        }
        @Bean MapperFactoryBean<XReviewDeliveryMapper> deliveryMapper(SqlSessionFactory sessions) {
            MapperFactoryBean<XReviewDeliveryMapper> factory = new MapperFactoryBean<>(XReviewDeliveryMapper.class);
            factory.setSqlSessionFactory(sessions);
            return factory;
        }
        @Bean XPostRepository posts(XPostMapper mapper) { return new MyBatisXPostRepository(mapper); }
        @Bean XReviewDeliveryStore deliveries(XReviewDeliveryMapper mapper) { return new MyBatisXReviewDeliveryStore(mapper); }
    }
}
