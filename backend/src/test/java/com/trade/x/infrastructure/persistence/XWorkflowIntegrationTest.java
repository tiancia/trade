package com.trade.x.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.ai.AiTextClient;
import com.trade.client.telegram.TelegramApi;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.telegram.dto.TelegramCallbackQuery;
import com.trade.client.telegram.dto.TelegramChat;
import com.trade.client.telegram.dto.TelegramInlineKeyboardMarkup;
import com.trade.client.telegram.dto.TelegramMessage;
import com.trade.client.telegram.dto.TelegramUpdate;
import com.trade.client.telegram.dto.TelegramUser;
import com.trade.client.x.XApi;
import com.trade.client.x.XClientProperties;
import com.trade.client.x.dto.XUser;
import com.trade.x.application.decision.XAiDraftGenerator;
import com.trade.x.application.port.XPostRepository;
import com.trade.x.application.port.XReviewDeliveryStore;
import com.trade.x.application.service.XPostService;
import com.trade.x.domain.model.XPost;
import com.trade.x.domain.model.XPostStatus;
import com.trade.x.domain.model.XReviewDecision;
import com.trade.x.domain.model.XReviewDelivery;
import com.trade.x.domain.model.XWorkflowPolicy;
import com.trade.x.infrastructure.config.XPublishingProperties;
import com.trade.x.infrastructure.config.XTelegramReviewProperties;
import com.trade.x.infrastructure.config.XWorkflowProperties;
import com.trade.x.infrastructure.publisher.XApiPostPublisher;
import com.trade.x.infrastructure.review.TelegramXReviewGateway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Actual application/adapters/DDL; only external AI, Telegram and X calls are replaced. */
@SpringJUnitConfig(XWorkflowIntegrationTest.Config.class)
class XWorkflowIntegrationTest {
    private static final long BOT = 4503599627370495L;
    private static final long CHAT = -1001234567890L;
    private static final long REVIEWER = 7001L;
    private static final long MESSAGE = 90L;
    private static final Instant NOW = Instant.parse("2026-10-03T03:00:00.123456789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @Autowired private XPostRepository posts;
    @Autowired private XReviewDeliveryStore deliveries;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void schema() throws Exception {
        for (String table : new String[]{"x_review_delivery", "x_review_polling", "x_publish_attempt",
                "x_post_history", "x_post", "x_account_gate"}) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        String sql;
        try (var input = new ClassPathResource("db/schema/x/schema.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Execute production DDL; H2 omits only the MySQL storage-engine options.
        sql = sql.replaceAll("(?m)^--.*$", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }

    @Test
    void configuredAiDraftRequiresBoundHumanApprovalAndPublishesExactlyOnceAcrossServiceRecreation() throws Exception {
        String body = "A clear system starts with one safe step 🌱.\nKeep the full reviewed wording.";
        String direction = "Practical engineering tradeoffs";
        XWorkflowProperties workflow = new XWorkflowProperties();
        workflow.setEnabled(true);
        workflow.setGenerationEnabled(true);
        workflow.setPublishingEnabled(true);
        workflow.setTargetUserId("42");
        workflow.getContent().setDirection(direction);
        workflow.getContent().setLanguage("English");
        workflow.getContent().setTone("clear and practical");
        workflow.getContent().setInstructions("Describe a general lesson; make no live factual claims.");
        workflow.getContent().setMinChars(10);
        workflow.getContent().setMaxChars(120);
        XWorkflowPolicy policy = workflow.policy();
        TelegramClientProperties telegramClient = new TelegramClientProperties();
        telegramClient.setEnabled(true);
        telegramClient.setBotToken("123456:OFFLINE_ONLY");
        XTelegramReviewProperties review = new XTelegramReviewProperties();
        review.setEnabled(true);
        review.setChatId(Long.toString(CHAT));
        review.setReviewerUserIds(List.of(REVIEWER));
        XClientProperties xClient = new XClientProperties();
        xClient.setEnabled(true);
        xClient.setApiKey("offline-key-fixture");
        xClient.setApiSecret("offline-secret-fixture");
        xClient.setAccessToken("offline-token-fixture");
        xClient.setAccessTokenSecret("offline-token-secret-fixture");
        XPublishingProperties publishing = new XPublishingProperties();
        publishing.setLivePublishingEnabled(true);
        AiTextClient ai = mock(AiTextClient.class);
        TelegramApi telegram = mock(TelegramApi.class);
        XApi x = mock(XApi.class);
        String aiJson = new ObjectMapper().writeValueAsString(Map.of("body", body,
                "reviewNote", "This is a general design lesson, without live factual assertions."));
        when(ai.generateJson(anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("GENERATING", jdbc.queryForObject("SELECT status FROM x_post", String.class));
            return aiJson;
        });
        TelegramUser bot = new TelegramUser(BOT, true, "Offline review bot", null, null);
        TelegramMessage message = new TelegramMessage(MESSAGE, NOW.getEpochSecond(),
                new TelegramChat(CHAT, "supergroup", "Review", null), bot, null);
        when(telegram.getMe()).thenReturn(bot);
        when(telegram.answerCallbackQuery(anyString(), anyString(), eq(false))).thenReturn(true);
        when(telegram.sendMessage(eq(Long.toString(CHAT)), anyString(), any(TelegramInlineKeyboardMarkup.class)))
                .thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    TelegramInlineKeyboardMarkup buttons = call.getArgument(2);
                    String deliveryId = buttons.inlineKeyboard().getFirst().getFirst().callbackData().substring(4);
                    XReviewDelivery reserved = deliveries.find(BOT, deliveryId).orElseThrow();
                    assertEquals("SENDING", reserved.status());
                    XPost persisted = posts.find(reserved.postId()).orElseThrow();
                    assertEquals(XPostStatus.PENDING_REVIEW, persisted.status());
                    assertEquals(body, persisted.body());
                    assertEquals(policy.content(), persisted.contentPolicy());
                    return message;
                });
        when(x.getMe()).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PUBLISHING", jdbc.queryForObject("SELECT status FROM x_post", String.class));
            return new XUser("42", "Fixture account", "fixture");
        });
        when(x.publishText(eq(body))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PUBLISHING", jdbc.queryForObject("SELECT status FROM x_publish_attempt", String.class));
            return new com.trade.client.x.dto.XPost("1234567890123456789", body);
        });
        XAiDraftGenerator generator = new XAiDraftGenerator(ai);
        TelegramXReviewGateway gateway = new TelegramXReviewGateway(telegram, telegramClient, review, workflow, deliveries, CLOCK);
        XApiPostPublisher publisher = new XApiPostPublisher(x, xClient, publishing, policy, CLOCK);
        XPostService service = new XPostService(posts, generator, gateway, publisher, policy,
                workflow.getGenerationFixedDelayMs(), CLOCK);

        XPost draft = service.generate().orElseThrow();
        service.runPublishing();

        assertEquals(XPostStatus.PENDING_REVIEW, posts.find(draft.id()).orElseThrow().status());
        assertEquals(body, draft.body());
        verifyNoInteractions(x);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).generateJson(prompt.capture());
        assertTrue(prompt.getValue().contains(direction));
        assertTrue(prompt.getValue().contains("10–120"));
        assertTrue(prompt.getValue().contains("English"));
        assertTrue(prompt.getValue().contains(policy.content().instructions()));
        ArgumentCaptor<String> shown = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TelegramInlineKeyboardMarkup> buttons = ArgumentCaptor.forClass(TelegramInlineKeyboardMarkup.class);
        verify(telegram).sendMessage(eq(Long.toString(CHAT)), shown.capture(), buttons.capture());
        assertTrue(shown.getValue().contains("【待发布正文】\n" + body + "\n【正文结束】"));
        assertTrue(shown.getValue().contains("目标账号：42"));
        assertTrue(shown.getValue().contains("正文版本：1"));
        String approvalData = buttons.getValue().inlineKeyboard().getFirst().getFirst().callbackData();
        assertTrue(approvalData.startsWith("x:a:"));
        String deliveryId = approvalData.substring(4);
        assertNotEquals(draft.id(), deliveryId);
        XReviewDelivery sent = deliveries.find(BOT, deliveryId).orElseThrow();
        assertEquals(draft.id(), sent.postId());
        assertEquals("SENT", sent.status());
        assertEquals(MESSAGE, sent.messageId());
        TelegramCallbackQuery unauthorized = callback("unauthorized", REVIEWER + 1, message, approvalData);
        TelegramCallbackQuery authorized = callback("approved-once", REVIEWER, message, approvalData);
        when(telegram.getUpdates(anyLong(), eq(100), eq(0), eq(List.of("callback_query"))))
                .thenReturn(List.of(new TelegramUpdate(20L, null, unauthorized)));

        service.runReviews();

        assertEquals(XPostStatus.PENDING_REVIEW, posts.find(draft.id()).orElseThrow().status());
        assertEquals(sent, deliveries.find(BOT, deliveryId).orElseThrow());
        verifyNoInteractions(x);
        when(telegram.getUpdates(anyLong(), eq(100), eq(0), eq(List.of("callback_query"))))
                .thenReturn(List.of(new TelegramUpdate(21L, null, authorized)));

        service.runReviews();

        XPost published = posts.find(draft.id()).orElseThrow();
        XReviewDelivery decided = deliveries.find(BOT, deliveryId).orElseThrow();
        assertEquals(XPostStatus.PUBLISHED, published.status());
        assertEquals("1234567890123456789", published.postId());
        assertEquals(body, published.body());
        assertEquals(policy.content(), published.contentPolicy());
        assertEquals("APPLIED", decided.decisionStatus());
        assertEquals("approved-once", decided.callbackId());
        assertTrue(decided.approved());
        assertEquals(NOW.truncatedTo(ChronoUnit.MICROS), decided.decidedAt());
        assertEquals(decided.decidedAt(), published.reviewedAt());
        assertEquals(22L, jdbc.queryForObject("SELECT next_offset FROM x_review_polling WHERE bot_id=?", Long.class, BOT));
        Clock restartClock = Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC);
        TelegramXReviewGateway restartedGateway = new TelegramXReviewGateway(telegram, telegramClient, review, workflow,
                deliveries, restartClock);
        XApiPostPublisher restartedPublisher = new XApiPostPublisher(x, xClient, publishing, policy, restartClock);
        XPostService restarted = new XPostService(posts, new XAiDraftGenerator(ai), restartedGateway, restartedPublisher,
                policy, workflow.getGenerationFixedDelayMs(), restartClock);
        when(telegram.getUpdates(anyLong(), eq(100), eq(0), eq(List.of("callback_query"))))
                .thenReturn(List.of(new TelegramUpdate(21L, null, authorized), new TelegramUpdate(22L, null, authorized)));

        restarted.runReviews();
        restarted.runPublishing();
        assertEquals(published, restarted.applyReview(new XReviewDecision(draft.id(), draft.contentVersion(), true,
                "telegram:" + REVIEWER, "Telegram button review", decided.decidedAt())));

        assertEquals(published, posts.find(draft.id()).orElseThrow());
        assertEquals(decided, deliveries.find(BOT, deliveryId).orElseThrow());
        assertEquals(23L, jdbc.queryForObject("SELECT next_offset FROM x_review_polling WHERE bot_id=?", Long.class, BOT));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_publish_attempt", Integer.class));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM x_publish_attempt", String.class));
        assertEquals("1234567890123456789", jdbc.queryForObject("SELECT external_post_id FROM x_publish_attempt", String.class));
        assertEquals(5, posts.history(draft.id()).size());
        verify(ai, times(1)).generateJson(anyString());
        verify(telegram, times(1)).sendMessage(eq(Long.toString(CHAT)), anyString(), any(TelegramInlineKeyboardMarkup.class));
        verify(telegram).getUpdates(0L, 100, 0, List.of("callback_query"));
        verify(telegram).getUpdates(21L, 100, 0, List.of("callback_query"));
        verify(telegram).getUpdates(22L, 100, 0, List.of("callback_query"));
        verify(x, times(1)).getMe();
        verify(x, times(1)).publishText(body);
        verifyNoMoreInteractions(x);
    }

    private static TelegramCallbackQuery callback(String id, long reviewer, TelegramMessage message, String data) {
        return new TelegramCallbackQuery(id, new TelegramUser(reviewer, false, "Reviewer", null, null),
                message, null, "instance", data);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config extends MyBatisXWorkflowPersistenceTest.Config {
        @Override
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:x_workflow_integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }
    }
}
