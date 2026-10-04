package com.trade.x.infrastructure.review;

import com.trade.client.telegram.TelegramApi;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.telegram.dto.TelegramCallbackQuery;
import com.trade.client.telegram.dto.TelegramChat;
import com.trade.client.telegram.dto.TelegramInlineKeyboardMarkup;
import com.trade.client.telegram.dto.TelegramMessage;
import com.trade.client.telegram.dto.TelegramUpdate;
import com.trade.client.telegram.dto.TelegramUser;
import com.trade.x.application.port.XReviewDeliveryStore;
import com.trade.x.domain.model.XReviewDecision;
import com.trade.x.domain.model.XReviewRequest;
import com.trade.x.domain.model.XReviewDelivery;
import com.trade.x.infrastructure.config.XTelegramReviewProperties;
import com.trade.x.infrastructure.config.XWorkflowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class TelegramXReviewGatewayTest {
    private static final long BOT_ID = 101L;
    private static final long CHAT_ID = -1001234567890L;
    private static final long REVIEWER_ID = 7001L;
    private static final long MESSAGE_ID = 81L;
    private static final String DELIVERY_ID = "2a847d59-a4d0-4c25-bc51-280235001ef1";
    private static final String FOREIGN_DELIVERY_ID = "2a847d59-a4d0-4c25-bc51-280235001ef2";
    private static final String POST_ID = "x-post-42";
    private static final String CALLBACK_ID = "callback-42";
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00.123456789Z");
    private static final Instant DB_NOW = NOW.truncatedTo(ChronoUnit.MICROS);
    private static final Instant EXPIRES = NOW.plusSeconds(3600);

    private final TelegramApi api = mock(TelegramApi.class);
    private final XReviewDeliveryStore deliveries = mock(XReviewDeliveryStore.class);
    private final Function<XReviewDecision, Boolean> consume = mock(Function.class);
    private final TelegramClientProperties client = new TelegramClientProperties();
    private final XTelegramReviewProperties settings = new XTelegramReviewProperties();
    private final XWorkflowProperties workflow = new XWorkflowProperties();
    private final Map<String, XReviewDelivery> stored = new LinkedHashMap<>();
    private final AtomicLong cursor = new AtomicLong();

    @BeforeEach
    void setup() {
        client.setEnabled(true);
        settings.setEnabled(true);
        settings.setChatId(Long.toString(CHAT_ID));
        settings.setReviewerUserIds(List.of(REVIEWER_ID));
        workflow.setEnabled(true);
        workflow.setTargetUserId("123456789");
        when(api.getMe()).thenReturn(user(BOT_ID, true));
        when(api.sendMessage(anyString(), anyString(), any(TelegramInlineKeyboardMarkup.class)))
                .thenReturn(message(CHAT_ID, MESSAGE_ID));
        when(api.getUpdates(anyLong(), eq(100), eq(0), eq(List.of("callback_query"))))
                .thenReturn(List.of());
        when(consume.apply(any())).thenReturn(true);

        when(deliveries.reserve(any())).thenAnswer(call -> {
            XReviewDelivery delivery = call.getArgument(0);
            boolean exists = stored.values().stream().anyMatch(value -> value.botId() == delivery.botId()
                    && value.postId().equals(delivery.postId()) && value.version() == delivery.version());
            if (exists) return false;
            stored.put(delivery.id(), delivery);
            return true;
        });
        when(deliveries.findForPost(anyLong(), anyString(), anyInt())).thenAnswer(call -> {
            long botId = call.getArgument(0);
            String postId = call.getArgument(1);
            int version = call.getArgument(2);
            return stored.values().stream().filter(value -> value.botId() == botId
                    && value.postId().equals(postId) && value.version() == version).findFirst();
        });
        when(deliveries.find(anyLong(), anyString())).thenAnswer(call -> {
            long botId = call.getArgument(0);
            XReviewDelivery value = stored.get(call.getArgument(1));
            return value != null && value.botId() == botId ? Optional.of(value) : Optional.empty();
        });
        when(deliveries.markSent(eq(BOT_ID), anyString(), anyLong())).thenAnswer(call -> {
            String id = call.getArgument(1);
            XReviewDelivery value = stored.get(id);
            stored.put(id, copy(value, "SENT", call.getArgument(2), value.callbackId(), value.approved(),
                    value.reviewer(), value.decidedAt(), value.decisionStatus()));
            return true;
        });
        doAnswer(call -> {
            String id = call.getArgument(1);
            XReviewDelivery value = stored.get(id);
            stored.put(id, copy(value, "UNKNOWN", value.messageId(), value.callbackId(), value.approved(),
                    value.reviewer(), value.decidedAt(), value.decisionStatus()));
            return null;
        }).when(deliveries).markUnknown(eq(BOT_ID), anyString());
        when(deliveries.recordDecision(eq(BOT_ID), anyString(), anyString(), anyBoolean(), anyString(), any(Instant.class)))
                .thenAnswer(call -> {
                    String id = call.getArgument(1);
                    XReviewDelivery value = stored.get(id);
                    if (value.callbackId() != null) return false;
                    stored.put(id, copy(value, value.status(), value.messageId(), call.getArgument(2),
                            call.getArgument(3), call.getArgument(4), call.getArgument(5), "PENDING"));
                    return true;
                });
        doAnswer(call -> {
            String id = call.getArgument(1);
            boolean applied = call.getArgument(2);
            XReviewDelivery value = stored.get(id);
            stored.put(id, copy(value, value.status(), value.messageId(), value.callbackId(), value.approved(),
                    value.reviewer(), value.decidedAt(), applied ? "APPLIED" : "INVALID"));
            return null;
        }).when(deliveries).finishDecision(eq(BOT_ID), anyString(), anyBoolean());
        when(deliveries.acquirePolling(eq(BOT_ID), anyString(), any(Instant.class), any(Instant.class)))
                .thenAnswer(call -> OptionalLong.of(cursor.get()));
        when(deliveries.renewPolling(eq(BOT_ID), anyString(), any(Instant.class), any(Instant.class))).thenReturn(true);
        when(deliveries.advancePolling(eq(BOT_ID), anyString(), anyLong(), any(Instant.class), any(Instant.class)))
                .thenAnswer(call -> {
                    cursor.set(call.getArgument(2));
                    return true;
                });
    }

    @Test
    void eachDisabledGateStopsSubmissionAndPollingBeforeApiOrStorage() {
        TelegramXReviewGateway gateway = gateway();
        settings.setEnabled(false);
        assertFalse(gateway.submit(request(1, "body", "context", EXPIRES)));
        gateway.poll(consume);
        settings.setEnabled(true);
        client.setEnabled(false);
        assertFalse(gateway.submit(request(1, "body", "context", EXPIRES)));
        gateway.poll(consume);
        client.setEnabled(true);
        workflow.setEnabled(false);
        assertFalse(gateway.submit(request(1, "body", "context", EXPIRES)));
        gateway.poll(consume);

        verifyNoInteractions(api, deliveries, consume);
    }

    @Test
    void submissionShowsWholeBodyVersionTargetAndButtonsWhileOnlyContextIsClipped() {
        String body = "🙂".repeat(3000);
        String context = "来源资料😀".repeat(1500);
        TelegramXReviewGateway gateway = gateway();

        assertTrue(gateway.submit(request(1, body, context, EXPIRES)));
        assertTrue(gateway.submit(request(1, body, context, EXPIRES)));
        assertTrue(gateway.submit(request(2, body, context, EXPIRES)));

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TelegramInlineKeyboardMarkup> buttons = ArgumentCaptor.forClass(TelegramInlineKeyboardMarkup.class);
        verify(api, times(2)).sendMessage(eq(Long.toString(CHAT_ID)), texts.capture(), buttons.capture());
        String shown = texts.getAllValues().getFirst();
        assertTrue(shown.contains("目标账号：123456789"));
        assertTrue(shown.contains("X草稿待审核"));
        assertTrue(shown.contains("草稿：" + POST_ID));
        assertTrue(shown.contains("正文版本：1"));
        assertTrue(shown.contains("【待发布正文】\n" + body + "\n【正文结束】"));
        assertTrue(shown.contains("正文完整保留"));
        assertFalse(shown.contains(context));
        assertTrue(shown.codePointCount(0, shown.length()) <= 4096);
        assertTrue(texts.getAllValues().get(1).contains("正文版本：2"));
        assertEquals(1, buttons.getAllValues().getFirst().inlineKeyboard().size());
        assertEquals(2, buttons.getAllValues().getFirst().inlineKeyboard().getFirst().size());
        String approve = buttons.getAllValues().getFirst().inlineKeyboard().getFirst().get(0).callbackData();
        String reject = buttons.getAllValues().getFirst().inlineKeyboard().getFirst().get(1).callbackData();
        assertEquals("x:a:", approve.substring(0, 4));
        assertEquals("x:r:" + approve.substring(4), reject);
        assertEquals(1, stored.get(approve.substring(4)).version());
        verify(deliveries, times(2)).reserve(any());
        verify(api).getMe();
        verifyNoInteractions(consume);
    }

    @Test
    void uncertainTransportFailureKeepsReservationAndNeverResendsThatVersion() {
        when(api.sendMessage(anyString(), anyString(), any(TelegramInlineKeyboardMarkup.class)))
                .thenThrow(new IllegalStateException("transport outcome unknown"));
        TelegramXReviewGateway gateway = gateway();

        assertFalse(gateway.submit(request(1, "body", "context", EXPIRES)));
        assertFalse(gateway.submit(request(1, "body", "context", EXPIRES)));

        assertEquals("UNKNOWN", stored.values().iterator().next().status());
        verify(deliveries).reserve(any());
        verify(deliveries).markUnknown(eq(BOT_ID), anyString());
        verify(api).sendMessage(anyString(), anyString(), any(TelegramInlineKeyboardMarkup.class));
        verify(deliveries, never()).markSent(anyLong(), anyString(), anyLong());
    }

    @Test
    void changedTargetAccountCannotSendPreviouslyGeneratedDraftToReview() {
        XReviewRequest oldTarget = new XReviewRequest(POST_ID, 1, "987654321", "body", "context", EXPIRES);

        assertFalse(gateway().submit(oldTarget));

        verifyNoInteractions(api, deliveries, consume);
    }

    @Test
    void completionDatabaseFailureDoesNotTurnPossiblySentMessageIntoSecondSend() {
        when(deliveries.markSent(eq(BOT_ID), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("completion database unavailable"));
        TelegramXReviewGateway gateway = gateway();

        assertFalse(gateway.submit(request(1, "body", "context", EXPIRES)));
        assertFalse(gateway.submit(request(1, "body", "context", EXPIRES)));

        verify(deliveries).reserve(any());
        verify(deliveries).markSent(eq(BOT_ID), anyString(), eq(MESSAGE_ID));
        verify(deliveries).markUnknown(eq(BOT_ID), anyString());
        verify(api).sendMessage(anyString(), anyString(), any(TelegramInlineKeyboardMarkup.class));
        assertEquals("UNKNOWN", stored.values().iterator().next().status());
    }

    @Test
    void unauthorizedReviewerChatMessageOrBotDeliveryCannotConsumeXReviewDecision() {
        stored.put(DELIVERY_ID, sentDelivery());
        XReviewDelivery foreign = new XReviewDelivery(FOREIGN_DELIVERY_ID, BOT_ID + 1, POST_ID,
                3, CHAT_ID, MESSAGE_ID, "SENT", EXPIRES, null, null, null, null, "PENDING");
        when(deliveries.find(BOT_ID, FOREIGN_DELIVERY_ID)).thenReturn(Optional.of(foreign));
        List<TelegramUpdate> updates = List.of(
                update(1, callback("wrong-reviewer", REVIEWER_ID + 1, false, CHAT_ID, MESSAGE_ID, DELIVERY_ID, "a")),
                update(2, callback("wrong-chat", REVIEWER_ID, false, CHAT_ID - 1, MESSAGE_ID, DELIVERY_ID, "a")),
                update(3, callback("wrong-message", REVIEWER_ID, false, CHAT_ID, MESSAGE_ID + 1, DELIVERY_ID, "a")),
                update(4, callback("bot-reviewer", REVIEWER_ID, true, CHAT_ID, MESSAGE_ID, DELIVERY_ID, "a")),
                update(5, callback("foreign-bot", REVIEWER_ID, false, CHAT_ID, MESSAGE_ID, FOREIGN_DELIVERY_ID, "a")));
        pollReturns(updates);

        gateway().poll(consume);

        verifyNoInteractions(consume);
        verify(deliveries, never()).recordDecision(anyLong(), anyString(), anyString(), anyBoolean(), anyString(), any());
        verify(deliveries, never()).finishDecision(anyLong(), anyString(), anyBoolean());
        assertEquals(6, cursor.get());
    }

    @Test
    void weiboCallbackPrefixCannotApproveAnXDeliveryEvenWithAuthorizedReviewer() {
        stored.put(DELIVERY_ID, sentDelivery());
        TelegramCallbackQuery weiboButton = new TelegramCallbackQuery(CALLBACK_ID, user(REVIEWER_ID, false),
                message(CHAT_ID, MESSAGE_ID), null, "chat-instance", "w:a:" + DELIVERY_ID);
        pollReturns(List.of(update(21, weiboButton)));

        gateway().poll(consume);

        verifyNoInteractions(consume);
        verify(deliveries, never()).find(anyLong(), anyString());
        verify(deliveries, never()).recordDecision(anyLong(), anyString(), anyString(), anyBoolean(), anyString(), any());
        verify(deliveries, never()).finishDecision(anyLong(), anyString(), anyBoolean());
        assertEquals(22, cursor.get());
    }

    @Test
    void approvalIsStoredBeforeBusinessConsumptionAndCursorAcknowledgement() {
        verifyDecisionFlow("a", true);
    }

    @Test
    void rejectionIsStoredBeforeBusinessConsumptionAndCursorAcknowledgement() {
        verifyDecisionFlow("r", false);
    }

    @Test
    void callbackAnswerFailureDoesNotUndoAppliedDecisionOrAdvanceCursorAgain() {
        stored.put(DELIVERY_ID, sentDelivery());
        pollReturns(List.of(update(21, authorizedCallback("a"))));
        when(api.answerCallbackQuery(eq(CALLBACK_ID), anyString(), eq(false)))
                .thenThrow(new IllegalStateException("answer unavailable"));

        assertDoesNotThrow(() -> gateway().poll(consume));

        assertEquals("APPLIED", stored.get(DELIVERY_ID).decisionStatus());
        assertEquals(22, cursor.get());
        verify(consume).apply(any());
        verify(deliveries).advancePolling(eq(BOT_ID), anyString(), eq(22L), any(), any());
    }

    @Test
    void temporaryBusinessFailureRetainsCursorAndReplaysStoredMicrosecondDecision() {
        Instant originalDecisionTime = NOW.minusSeconds(10);
        XReviewDelivery value = sentDelivery();
        stored.put(DELIVERY_ID, copy(value, "SENT", MESSAGE_ID, CALLBACK_ID, true,
                "telegram:" + REVIEWER_ID, originalDecisionTime, "PENDING"));
        pollReturns(List.of(update(21, authorizedCallback("a"))));
        when(consume.apply(any())).thenThrow(new IllegalStateException("business database unavailable")).thenReturn(true);
        TelegramXReviewGateway gateway = gateway();

        assertThrows(IllegalStateException.class, () -> gateway.poll(consume));
        assertEquals(0, cursor.get());
        verify(deliveries, never()).advancePolling(anyLong(), anyString(), anyLong(), any(), any());
        assertDoesNotThrow(() -> gateway.poll(consume));

        ArgumentCaptor<XReviewDecision> decisions = ArgumentCaptor.forClass(XReviewDecision.class);
        verify(consume, times(2)).apply(decisions.capture());
        assertEquals(decisions.getAllValues().getFirst(), decisions.getAllValues().get(1));
        assertEquals(originalDecisionTime.truncatedTo(ChronoUnit.MICROS), decisions.getValue().decidedAt());
        verify(deliveries, never()).recordDecision(anyLong(), anyString(), anyString(), anyBoolean(), anyString(), any());
        verify(api, times(2)).getUpdates(0L, 100, 0, List.of("callback_query"));
        verify(deliveries, times(2)).releasePolling(eq(BOT_ID), anyString());
        assertEquals(22, cursor.get());
    }

    @Test
    void alreadyAppliedDecisionAcknowledgesDuplicateCallbackWithoutBusinessReplay() {
        XReviewDelivery value = sentDelivery();
        stored.put(DELIVERY_ID, copy(value, "SENT", MESSAGE_ID, CALLBACK_ID, true,
                "telegram:" + REVIEWER_ID, DB_NOW.minusSeconds(1), "APPLIED"));
        pollReturns(List.of(update(21, authorizedCallback("a"))));

        gateway().poll(consume);

        verifyNoInteractions(consume);
        verify(deliveries, never()).recordDecision(anyLong(), anyString(), anyString(), anyBoolean(), anyString(), any());
        verify(deliveries, never()).finishDecision(anyLong(), anyString(), anyBoolean());
        verify(api).answerCallbackQuery(eq(CALLBACK_ID), anyString(), eq(false));
        assertEquals(22, cursor.get());
    }

    @Test
    void expiredOrOversizedReviewBodyCannotReserveOrSendMessage() {
        TelegramXReviewGateway gateway = gateway();

        assertFalse(gateway.submit(request(1, "body", "context", DB_NOW)));
        assertThrows(IllegalArgumentException.class,
                () -> gateway.submit(request(1, "🙂".repeat(4096), "context", EXPIRES)));

        verifyNoInteractions(api, deliveries, consume);

        stored.put(DELIVERY_ID, new XReviewDelivery(DELIVERY_ID, BOT_ID, POST_ID, 3, CHAT_ID,
                MESSAGE_ID, "SENT", DB_NOW, null, null, null, null, "PENDING"));
        pollReturns(List.of(update(21, authorizedCallback("a"))));
        gateway.poll(consume);

        verifyNoInteractions(consume);
        verify(deliveries, never()).recordDecision(anyLong(), anyString(), anyString(), anyBoolean(), anyString(), any());
        verify(deliveries, never()).finishDecision(anyLong(), anyString(), anyBoolean());
        assertEquals(22, cursor.get());
    }

    @Test
    void occupiedLeaseSkipsUpdatesAndLostLeaseStopsBeforeConsuming() {
        stored.put(DELIVERY_ID, sentDelivery());
        when(deliveries.acquirePolling(eq(BOT_ID), anyString(), any(), any())).thenReturn(OptionalLong.empty());
        TelegramXReviewGateway gateway = gateway();

        gateway.poll(consume);
        verify(api, never()).getUpdates(anyLong(), anyInt(), anyInt(), anyList());
        verify(deliveries, never()).releasePolling(anyLong(), anyString());

        when(deliveries.acquirePolling(eq(BOT_ID), anyString(), any(), any())).thenReturn(OptionalLong.of(0));
        when(deliveries.renewPolling(eq(BOT_ID), anyString(), any(), any())).thenReturn(false);
        pollReturns(List.of(update(21, authorizedCallback("a"))));
        gateway.poll(consume);

        verifyNoInteractions(consume);
        verify(deliveries, never()).recordDecision(anyLong(), anyString(), anyString(), anyBoolean(), anyString(), any());
        verify(deliveries, never()).advancePolling(anyLong(), anyString(), anyLong(), any(), any());
        verify(deliveries).releasePolling(eq(BOT_ID), anyString());
        assertEquals(0, cursor.get());
    }

    private void verifyDecisionFlow(String action, boolean approved) {
        stored.put(DELIVERY_ID, sentDelivery());
        pollReturns(List.of(update(21, authorizedCallback(action))));

        gateway().poll(consume);

        ArgumentCaptor<XReviewDecision> decision = ArgumentCaptor.forClass(XReviewDecision.class);
        InOrder ordered = inOrder(deliveries, consume, api);
        ordered.verify(deliveries).recordDecision(BOT_ID, DELIVERY_ID, CALLBACK_ID, approved,
                "telegram:" + REVIEWER_ID, DB_NOW);
        ordered.verify(consume).apply(decision.capture());
        ordered.verify(deliveries).finishDecision(BOT_ID, DELIVERY_ID, true);
        ordered.verify(deliveries).advancePolling(eq(BOT_ID), anyString(), eq(22L), eq(DB_NOW), eq(DB_NOW.plusSeconds(60)));
        ordered.verify(api).answerCallbackQuery(eq(CALLBACK_ID), anyString(), eq(false));
        assertEquals(new XReviewDecision(POST_ID, 3, approved,
                "telegram:" + REVIEWER_ID, "Telegram button review", DB_NOW), decision.getValue());
        assertEquals("APPLIED", stored.get(DELIVERY_ID).decisionStatus());
        assertEquals(22, cursor.get());
    }

    private TelegramXReviewGateway gateway() {
        return new TelegramXReviewGateway(api, client, settings, workflow, deliveries,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void pollReturns(List<TelegramUpdate> updates) {
        when(api.getUpdates(anyLong(), eq(100), eq(0), eq(List.of("callback_query")))).thenReturn(updates);
    }

    private static XReviewRequest request(int version, String body, String context, Instant expiresAt) {
        return new XReviewRequest(POST_ID, version, "123456789", body, context, expiresAt);
    }

    private static XReviewDelivery sentDelivery() {
        return new XReviewDelivery(DELIVERY_ID, BOT_ID, POST_ID, 3, CHAT_ID, MESSAGE_ID,
                "SENT", EXPIRES, null, null, null, null, "PENDING");
    }

    private static XReviewDelivery copy(XReviewDelivery value, String status, Long messageId,
            String callbackId, Boolean approved, String reviewer, Instant decidedAt, String decisionStatus) {
        return new XReviewDelivery(value.id(), value.botId(), value.postId(), value.version(), value.chatId(),
                messageId, status, value.expiresAt(), callbackId, approved, reviewer, decidedAt, decisionStatus);
    }

    private static TelegramUser user(long userId, boolean bot) {
        return new TelegramUser(userId, bot, bot ? "Review bot" : "Reviewer", null, null);
    }

    private static TelegramMessage message(long chatId, long messageId) {
        return new TelegramMessage(messageId, NOW.getEpochSecond(), new TelegramChat(chatId, "supergroup", null, null),
                user(BOT_ID, true), null);
    }

    private static TelegramCallbackQuery authorizedCallback(String action) {
        return callback(CALLBACK_ID, REVIEWER_ID, false, CHAT_ID, MESSAGE_ID, DELIVERY_ID, action);
    }

    private static TelegramCallbackQuery callback(String callbackId, long reviewerId, boolean reviewerIsBot,
            long chatId, long messageId, String deliveryId, String action) {
        return new TelegramCallbackQuery(callbackId, user(reviewerId, reviewerIsBot), message(chatId, messageId),
                null, "chat-instance", "x:" + action + ":" + deliveryId);
    }

    private static TelegramUpdate update(long updateId, TelegramCallbackQuery callback) {
        return new TelegramUpdate(updateId, null, callback);
    }
}
