package com.trade.weibo.infrastructure.persistence;

import com.trade.client.ai.AiTextClient;
import com.trade.client.telegram.TelegramApi;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.telegram.dto.TelegramCallbackQuery;
import com.trade.client.telegram.dto.TelegramChat;
import com.trade.client.telegram.dto.TelegramInlineKeyboardMarkup;
import com.trade.client.telegram.dto.TelegramMessage;
import com.trade.client.telegram.dto.TelegramUpdate;
import com.trade.client.telegram.dto.TelegramUser;
import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.client.weibo.WeiboPublishResult;
import com.trade.weibo.application.decision.AiWeiboDraftGenerator;
import com.trade.weibo.application.port.HotEventSource;
import com.trade.weibo.application.port.WeiboAccountTokenRepository;
import com.trade.weibo.application.port.WeiboPostRepository;
import com.trade.weibo.application.port.WeiboReviewDeliveryStore;
import com.trade.weibo.application.service.WeiboPostService;
import com.trade.weibo.application.service.WeiboPublishingService;
import com.trade.weibo.domain.model.HotEvent;
import com.trade.weibo.domain.model.ReviewDecision;
import com.trade.weibo.domain.model.WeiboAccountToken;
import com.trade.weibo.domain.model.WeiboPost;
import com.trade.weibo.domain.model.WeiboPostStatus;
import com.trade.weibo.domain.model.WeiboReviewDelivery;
import com.trade.weibo.domain.model.WeiboWorkflowPolicy;
import com.trade.weibo.infrastructure.config.WeiboTelegramReviewProperties;
import com.trade.weibo.infrastructure.config.WeiboWorkflowProperties;
import com.trade.weibo.infrastructure.review.TelegramHumanReviewGateway;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.mockito.ArgumentCaptor;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(MyBatisWeiboReviewDeliveryStoreTest.Config.class)
class MyBatisWeiboReviewDeliveryStoreTest {
    private static final long BOT = 4503599627370495L;
    private static final long CHAT = -1001234567890L;
    private static final Instant NOW = Instant.parse("2026-10-03T01:00:00.123456Z");
    @Autowired private WeiboReviewDeliveryStore store;
    @Autowired private WeiboReviewDeliveryMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WeiboPostRepository posts;

    @BeforeEach
    void schema() throws Exception {
        for (String table : new String[]{"weibo_review_delivery", "weibo_review_polling", "weibo_publish_attempt",
                "weibo_post_history", "weibo_post", "weibo_account_gate", "weibo_oauth_state", "weibo_account_token"}) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        currentSchema();
        insertPost("post-1");
        insertPost("post-2");
    }

    @Test
    void durableDeliveryIsBoundToBotPostAndExactVersion() {
        WeiboReviewDelivery initial = delivery("delivery-1", BOT, "post-1", 1);

        assertTrue(store.reserve(initial));
        assertEquals(initial, new MyBatisWeiboReviewDeliveryStore(mapper).find(BOT, initial.id()).orElseThrow());
        assertEquals(initial, store.findForPost(BOT, "post-1", 1).orElseThrow());
        assertTrue(store.find(BOT + 1, initial.id()).isEmpty());
        assertTrue(store.findForPost(BOT, "post-1", 2).isEmpty());
        assertFalse(store.reserve(delivery("duplicate-version", BOT, "post-1", 1)));
        assertTrue(store.reserve(delivery("next-version", BOT, "post-1", 2)));
        assertTrue(store.reserve(delivery("other-bot", BOT + 1, "post-1", 1)));
        assertFalse(store.reserve(delivery("delivery-1", BOT + 1, "post-2", 1)));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM weibo_review_delivery", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM weibo_review_delivery", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT MIN(id) FROM weibo_review_delivery", Long.class) > 0);
        assertEquals("post-1", jdbc.queryForObject("SELECT p.post_key FROM weibo_review_delivery d"
                + " JOIN weibo_post p ON p.post_key=d.post_id WHERE d.delivery_key='delivery-1'", String.class));
    }

    @Test
    void concurrentReservationsCannotDuplicateOneBotPostVersion() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return store.reserve(delivery("first", BOT, "post-1", 1));
            });
            var second = executor.submit(() -> {
                start.await();
                return store.reserve(delivery("second", BOT, "post-1", 1));
            });
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weibo_review_delivery", Integer.class));
    }

    @Test
    void foreignKeyFailuresAreNotMistakenForDuplicateReservations() {
        assertThrows(DataIntegrityViolationException.class,
                () -> store.reserve(delivery("missing-post", BOT, "absent", 1)));
        assertTrue(store.find(BOT, "missing-post").isEmpty());
    }

    @Test
    void deliveryStateChangesRequireSendingStatusAndMatchingBot() {
        assertTrue(store.reserve(delivery("sent", BOT, "post-1", 1)));
        assertFalse(store.markSent(BOT + 1, "sent", 10));
        assertTrue(store.markSent(BOT, "sent", 4503599627370494L));
        assertFalse(store.markSent(BOT, "sent", 11));
        store.markUnknown(BOT, "sent");
        WeiboReviewDelivery sent = store.find(BOT, "sent").orElseThrow();
        assertEquals("SENT", sent.status());
        assertEquals(4503599627370494L, sent.messageId());

        assertTrue(store.reserve(delivery("unknown", BOT, "post-2", 1)));
        store.markUnknown(BOT + 1, "unknown");
        assertEquals("SENDING", store.find(BOT, "unknown").orElseThrow().status());
        store.markUnknown(BOT, "unknown");
        assertEquals("UNKNOWN", store.find(BOT, "unknown").orElseThrow().status());
        assertFalse(store.markSent(BOT, "unknown", 12));
        assertFalse(store.recordDecision(BOT, "unknown", "callback-unknown", true, "reviewer", NOW));
    }

    @Test
    void firstDecisionSurvivesRestartAtMicrosecondPrecisionAndCannotBeOverwritten() {
        assertTrue(store.reserve(delivery("decision", BOT, "post-1", 1)));
        assertFalse(store.recordDecision(BOT, "decision", "too-early", true, "reviewer", NOW));
        store.finishDecision(BOT, "decision", true);
        assertNull(store.find(BOT, "decision").orElseThrow().decisionStatus());
        assertTrue(store.markSent(BOT, "decision", 10));
        Instant nanoseconds = Instant.parse("2026-10-03T01:00:01.123456789Z");

        assertTrue(store.recordDecision(BOT, "decision", "callback-1", true, "telegram:42", nanoseconds));
        assertFalse(store.recordDecision(BOT, "decision", "callback-2", false, "telegram:99", NOW));
        WeiboReviewDelivery recorded = new MyBatisWeiboReviewDeliveryStore(mapper).find(BOT, "decision").orElseThrow();

        assertEquals("callback-1", recorded.callbackId());
        assertTrue(recorded.approved());
        assertEquals("telegram:42", recorded.reviewer());
        assertEquals(nanoseconds.truncatedTo(ChronoUnit.MICROS), recorded.decidedAt());
        assertEquals("PENDING", recorded.decisionStatus());
        store.finishDecision(BOT + 1, "decision", true);
        assertEquals("PENDING", store.find(BOT, "decision").orElseThrow().decisionStatus());
        store.finishDecision(BOT, "decision", true);
        store.finishDecision(BOT, "decision", false);
        assertEquals("APPLIED", store.find(BOT, "decision").orElseThrow().decisionStatus());
        assertEquals(recorded.decidedAt(), store.find(BOT, "decision").orElseThrow().decidedAt());
    }

    @Test
    void concurrentOpposingDecisionsPersistOnlyOneAndInvalidCompletionCannotChange() throws Exception {
        assertTrue(store.reserve(delivery("decision", BOT, "post-1", 1)));
        assertTrue(store.markSent(BOT, "decision", 10));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return store.recordDecision(BOT, "decision", "approve", true, "approver", NOW);
            });
            var second = executor.submit(() -> {
                start.await();
                return store.recordDecision(BOT, "decision", "reject", false, "rejecter", NOW.plusNanos(1000));
            });
            start.countDown();
            boolean approvedWon = first.get(10, TimeUnit.SECONDS);
            assertNotEquals(approvedWon, second.get(10, TimeUnit.SECONDS));
            WeiboReviewDelivery recorded = store.find(BOT, "decision").orElseThrow();
            assertEquals(approvedWon, recorded.approved());
            assertEquals(approvedWon ? "approve" : "reject", recorded.callbackId());
        }
        store.finishDecision(BOT, "decision", false);
        store.finishDecision(BOT, "decision", true);
        assertEquals("INVALID", store.find(BOT, "decision").orElseThrow().decisionStatus());
    }

    @Test
    void concurrentPollersAcquireExactlyOneLeasePerBot() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return store.acquirePolling(BOT, "first", NOW, NOW.plusSeconds(30));
            });
            var second = executor.submit(() -> {
                start.await();
                return store.acquirePolling(BOT, "second", NOW, NOW.plusSeconds(30));
            });
            start.countDown();
            var firstLease = first.get(10, TimeUnit.SECONDS);
            var secondLease = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstLease.isPresent(), secondLease.isPresent());
            assertEquals(0L, firstLease.isPresent() ? firstLease.getAsLong() : secondLease.getAsLong());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weibo_review_polling", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT id FROM weibo_review_polling WHERE bot_id=?", Long.class, BOT) > 0);
        assertEquals(0L, store.acquirePolling(BOT + 1, "independent-bot", NOW, NOW.plusSeconds(30)).orElseThrow());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM weibo_review_polling", Integer.class));
    }

    @Test
    void offsetSurvivesNewStoreAndOldOwnerCannotAdvanceOrReleaseNewLease() {
        assertEquals(0L, store.acquirePolling(BOT, "old", NOW, NOW.plusSeconds(20)).orElseThrow());
        assertTrue(store.acquirePolling(BOT, "other", NOW.plusSeconds(1), NOW.plusSeconds(30)).isEmpty());
        assertFalse(store.renewPolling(BOT, "other", NOW.plusSeconds(1), NOW.plusSeconds(30)));
        assertTrue(store.renewPolling(BOT, "old", NOW.plusSeconds(5), NOW.plusSeconds(30)));
        assertTrue(store.advancePolling(BOT, "old", 62, NOW.plusSeconds(10), NOW.plusSeconds(40)));
        assertFalse(store.advancePolling(BOT, "old", 61, NOW.plusSeconds(11), NOW.plusSeconds(40)));
        store.releasePolling(BOT, "other");
        assertTrue(store.acquirePolling(BOT, "other", NOW.plusSeconds(12), NOW.plusSeconds(40)).isEmpty());

        MyBatisWeiboReviewDeliveryStore restarted = new MyBatisWeiboReviewDeliveryStore(mapper);
        assertEquals(62L, restarted.acquirePolling(BOT, "new", NOW.plusSeconds(40), NOW.plusSeconds(70)).orElseThrow());
        assertFalse(store.advancePolling(BOT, "old", 100, NOW.plusSeconds(41), NOW.plusSeconds(70)));
        assertFalse(store.renewPolling(BOT, "old", NOW.plusSeconds(41), NOW.plusSeconds(70)));
        store.releasePolling(BOT, "old");
        assertTrue(store.acquirePolling(BOT, "intruder", NOW.plusSeconds(42), NOW.plusSeconds(70)).isEmpty());
        assertFalse(restarted.advancePolling(BOT, "new", 100, NOW.plusSeconds(70), NOW.plusSeconds(90)));
        restarted.releasePolling(BOT, "new");
        assertEquals(62L, store.acquirePolling(BOT, "third", NOW.plusSeconds(71), NOW.plusSeconds(90)).orElseThrow());
    }

    @Test
    void invalidLeaseAndNegativeOffsetDoNotModifyCursor() {
        assertThrows(IllegalArgumentException.class, () -> store.acquirePolling(BOT, " ", NOW, NOW.plusSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> store.acquirePolling(BOT, "owner", NOW, NOW));
        assertEquals(0L, store.acquirePolling(BOT, "owner", NOW, NOW.plusSeconds(30)).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> store.advancePolling(BOT, "owner", -1, NOW.plusSeconds(1), NOW.plusSeconds(30)));
        store.releasePolling(BOT, "owner");
        assertEquals(0L, store.acquirePolling(BOT, "next", NOW.plusSeconds(2), NOW.plusSeconds(30)).orElseThrow());
    }

    @Test
    void generatedAiDraftIsReviewedAndPublishedOnceAcrossGatewayAndServiceRecreation() {
        jdbc.update("DELETE FROM weibo_post");
        String body = "第一行完整观点😀\n第二行保留来源与谨慎结论。";
        long reviewerId = 42L;
        long messageId = 90L;
        Instant clickedAt = NOW.plusSeconds(1).plusNanos(789);
        Instant persistedDecisionAt = clickedAt.truncatedTo(ChronoUnit.MICROS);
        Clock clickClock = Clock.fixed(clickedAt, ZoneOffset.UTC);
        WeiboWorkflowProperties workflow = new WeiboWorkflowProperties();
        workflow.setEnabled(true);
        workflow.setGenerationEnabled(true);
        workflow.setPublishingEnabled(true);
        workflow.setTargetUid("uid");
        WeiboWorkflowPolicy policy = workflow.policy();
        TelegramClientProperties client = new TelegramClientProperties();
        client.setEnabled(true);
        client.setBotToken("123456:OFFLINE_ONLY");
        WeiboTelegramReviewProperties reviewSettings = new WeiboTelegramReviewProperties();
        reviewSettings.setEnabled(true);
        reviewSettings.setChatId(Long.toString(CHAT));
        reviewSettings.setReviewerUserIds(List.of(reviewerId));
        WeiboClientProperties weiboSettings = new WeiboClientProperties();
        weiboSettings.setLivePublishingEnabled(true);

        AiTextClient ai = mock(AiTextClient.class);
        TelegramApi telegram = mock(TelegramApi.class);
        WeiboApi weibo = mock(WeiboApi.class);
        WeiboAccountTokenRepository tokens = mock(WeiboAccountTokenRepository.class);
        when(ai.generateJson(anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return "{\"body\":\"第一行完整观点😀\\n第二行保留来源与谨慎结论。\",\"reviewNote\":\"请核对事实来源，不做未经证实的推断。\"}";
        });
        when(tokens.findValidForUid(eq("uid"), any(Instant.class)))
                .thenReturn(Optional.of(new WeiboAccountToken("uid", "offline-token-fixture", NOW.plusSeconds(3600))));
        TelegramUser bot = new TelegramUser(BOT, true, "Offline review bot", null, null);
        TelegramMessage message = new TelegramMessage(messageId, clickedAt.getEpochSecond(),
                new TelegramChat(CHAT, "supergroup", "Review", null), bot, null);
        when(telegram.getMe()).thenReturn(bot);
        when(telegram.sendMessage(eq(Long.toString(CHAT)), anyString(), any(TelegramInlineKeyboardMarkup.class)))
                .thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    TelegramInlineKeyboardMarkup buttons = call.getArgument(2);
                    String deliveryId = buttons.inlineKeyboard().getFirst().getFirst().callbackData().substring(4);
                    WeiboReviewDelivery reserved = store.find(BOT, deliveryId).orElseThrow();
                    assertEquals("SENDING", reserved.status());
                    assertEquals(WeiboPostStatus.PENDING_REVIEW, posts.find(reserved.postId()).orElseThrow().status());
                    return message;
                });
        when(weibo.publishText(eq("offline-token-fixture"), eq(body))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PUBLISHING", jdbc.queryForObject("SELECT status FROM weibo_post", String.class));
            assertEquals("PUBLISHING", jdbc.queryForObject("SELECT status FROM weibo_publish_attempt", String.class));
            return new WeiboPublishResult("weibo-published-once", "mid-1", null, null);
        });
        HotEvent event = new HotEvent("待评论热点", "https://example.test/event", "已提供的来源摘要", NOW.minusSeconds(60), NOW);
        HotEventSource source = () -> List.of(event);
        AiWeiboDraftGenerator generator = new AiWeiboDraftGenerator(ai);
        WeiboPublishingService publisher = new WeiboPublishingService(weibo, tokens, clickClock, weiboSettings, policy);
        TelegramHumanReviewGateway gateway = new TelegramHumanReviewGateway(telegram, client, reviewSettings,
                workflow, store, clickClock);
        WeiboPostService generation = new WeiboPostService(posts, source, generator, gateway, publisher, policy,
                Clock.fixed(NOW, ZoneOffset.UTC));

        WeiboPost draft = generation.generate(event).orElseThrow();

        assertEquals(WeiboPostStatus.PENDING_REVIEW, draft.status());
        assertEquals(body, draft.body());
        ArgumentCaptor<String> shown = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TelegramInlineKeyboardMarkup> buttons = ArgumentCaptor.forClass(TelegramInlineKeyboardMarkup.class);
        verify(telegram).sendMessage(eq(Long.toString(CHAT)), shown.capture(), buttons.capture());
        assertTrue(shown.getValue().contains("【待发布正文】\n" + body + "\n【正文结束】"));
        String approvalData = buttons.getValue().inlineKeyboard().getFirst().getFirst().callbackData();
        String deliveryId = approvalData.substring(4);
        assertNotEquals(draft.id(), deliveryId);
        WeiboReviewDelivery sent = store.find(BOT, deliveryId).orElseThrow();
        assertEquals(draft.id(), sent.postId());
        assertEquals(draft.contentVersion(), sent.version());
        assertEquals("SENT", sent.status());
        assertEquals(messageId, sent.messageId());
        TelegramCallbackQuery callback = new TelegramCallbackQuery("callback-once",
                new TelegramUser(reviewerId, false, "Reviewer", null, null), message, null, "instance", approvalData);
        when(telegram.getUpdates(anyLong(), eq(100), eq(0), eq(List.of("callback_query"))))
                .thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    long offset = call.getArgument(0);
                    return List.of(new TelegramUpdate(offset == 0 ? 21L : 22L, null, callback));
                });
        WeiboPostService reviewing = new WeiboPostService(posts, source, generator, gateway, publisher, policy, clickClock);

        reviewing.runReviews();

        WeiboPost published = posts.find(draft.id()).orElseThrow();
        WeiboReviewDelivery decided = store.find(BOT, deliveryId).orElseThrow();
        assertEquals(WeiboPostStatus.PUBLISHED, published.status());
        assertEquals("weibo-published-once", published.weiboId());
        assertEquals(body, published.body());
        assertEquals(persistedDecisionAt, decided.decidedAt());
        assertEquals(persistedDecisionAt, published.reviewedAt());
        assertEquals("APPLIED", decided.decisionStatus());
        assertEquals("callback-once", decided.callbackId());
        assertTrue(decided.approved());
        assertEquals(22L, jdbc.queryForObject("SELECT next_offset FROM weibo_review_polling WHERE bot_id=?", Long.class, BOT));
        Clock restartClock = Clock.fixed(clickedAt.plusSeconds(2), ZoneOffset.UTC);
        TelegramHumanReviewGateway restartedGateway = new TelegramHumanReviewGateway(telegram, client, reviewSettings,
                workflow, store, restartClock);
        WeiboPublishingService restartedPublisher = new WeiboPublishingService(weibo, tokens, restartClock, weiboSettings, policy);
        WeiboPostService restarted = new WeiboPostService(posts, source, new AiWeiboDraftGenerator(ai), restartedGateway,
                restartedPublisher, policy, restartClock);

        restarted.runReviews();
        assertEquals(published, restarted.applyReview(new ReviewDecision("weibo", draft.id(), draft.contentVersion(),
                true, "telegram:" + reviewerId, "Telegram button review", decided.decidedAt())));
        restarted.runPublishing();

        assertEquals(published, posts.find(draft.id()).orElseThrow());
        assertEquals(decided, store.find(BOT, deliveryId).orElseThrow());
        assertEquals(23L, jdbc.queryForObject("SELECT next_offset FROM weibo_review_polling WHERE bot_id=?", Long.class, BOT));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM weibo_publish_attempt", Integer.class));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM weibo_publish_attempt", String.class));
        assertEquals(5, posts.history(draft.id()).size());
        verify(ai, times(1)).generateJson(anyString());
        verify(telegram, times(1)).sendMessage(eq(Long.toString(CHAT)), anyString(), any(TelegramInlineKeyboardMarkup.class));
        verify(telegram).getUpdates(0L, 100, 0, List.of("callback_query"));
        verify(telegram).getUpdates(22L, 100, 0, List.of("callback_query"));
        verify(weibo, times(1)).publishText("offline-token-fixture", body);
        verifyNoMoreInteractions(weibo);
    }

    private void currentSchema() throws Exception {
        String sql;
        try (var input = new ClassPathResource("db/schema/weibo/schema.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Run production DDL; remove only MySQL storage-engine options for H2's MySQL mode.
        sql = sql.replaceAll("(?m)^--.*$", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }

    private void insertPost(String id) {
        jdbc.update("INSERT INTO weibo_post (post_key,target_uid,event_key,event_json,content_version,revision,status,"
                        + "created_at,updated_at,expires_at) VALUES (?,'uid',?,'{}',1,0,'PENDING_REVIEW',?,?,?)",
                id, id, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(3600)));
    }

    private static WeiboReviewDelivery delivery(String id, long botId, String postId, int version) {
        return new WeiboReviewDelivery(id, botId, postId, version, CHAT, null, "SENDING", NOW.plusSeconds(3600),
                null, null, null, null, null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:weibo_review_delivery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new ClassPathResource("mapper/weibo/WeiboReviewDeliveryMapper.xml"),
                    new ClassPathResource("mapper/weibo/WeiboPostMapper.xml"));
            return factory.getObject();
        }
        @Bean MapperFactoryBean<WeiboReviewDeliveryMapper> mapper(SqlSessionFactory sessions) {
            MapperFactoryBean<WeiboReviewDeliveryMapper> factory = new MapperFactoryBean<>(WeiboReviewDeliveryMapper.class);
            factory.setSqlSessionFactory(sessions);
            return factory;
        }
        @Bean WeiboReviewDeliveryStore store(WeiboReviewDeliveryMapper mapper) {
            return new MyBatisWeiboReviewDeliveryStore(mapper);
        }
        @Bean MapperFactoryBean<WeiboPostMapper> postMapper(SqlSessionFactory sessions) {
            MapperFactoryBean<WeiboPostMapper> factory = new MapperFactoryBean<>(WeiboPostMapper.class);
            factory.setSqlSessionFactory(sessions);
            return factory;
        }
        @Bean WeiboPostRepository posts(WeiboPostMapper mapper) { return new MyBatisWeiboPostRepository(mapper); }
    }
}
