package com.trade.x.infrastructure.publisher;

import com.trade.client.x.*;
import com.trade.client.x.dto.XUser;
import com.trade.x.domain.model.*;
import com.trade.x.domain.exception.XPublishingException;
import com.trade.x.infrastructure.config.XPublishingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XApiPostPublisherTest {
    private final Instant now = Instant.parse("2026-10-03T00:00:00Z");
    private final XApi api = mock(XApi.class);
    private final XClientProperties client = new XClientProperties();
    private final XPublishingProperties settings = new XPublishingProperties();
    private final XWorkflowPolicy policy = new XWorkflowPolicy(true, true, true, "123",
            new XContentPolicy("编程", "简体中文", "自然", "", 5, 120), 5, 3,
            Duration.ofHours(6), Duration.ofMinutes(30), Duration.ofMinutes(5));
    private final AtomicReference<Instant> time = new AtomicReference<>(now);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    };
    private XApiPostPublisher publisher;
    private XPost pending;
    private XPost claimed;

    @BeforeEach void setup() {
        client.setEnabled(true); client.setApiKey("fake-key"); client.setApiSecret("fake-secret");
        client.setAccessToken("fake-token"); client.setAccessTokenSecret("fake-token-secret");
        settings.setLivePublishingEnabled(true);
        publisher = new XApiPostPublisher(api, client, settings, policy, clock);
        pending = XPost.generating("123", "fixture", now, policy).generated(new GeneratedXPost("完整审核后的正文。", "一般观点"), now);
        claimed = pending.review(1, true, "telegram:42", "approved", now, now).claim(now);
        when(api.getMe()).thenReturn(new XUser("123", "test", "test"));
        when(api.publishText(claimed.body())).thenReturn(new com.trade.client.x.dto.XPost("999", claimed.body()));
    }

    @Test void onlyClaimedReviewedTextIsSentAfterVerifyingOwner() {
        assertEquals("999", publisher.publish(claimed));
        var order = inOrder(api); order.verify(api).getMe(); order.verify(api).publishText(claimed.body());
        verifyNoMoreInteractions(api);
    }
    @Test void mismatchedCredentialOwnerNeverPosts() {
        when(api.getMe()).thenReturn(new XUser("456", "other", "other"));
        assertThrows(XPublishingException.class, () -> publisher.publish(claimed));
        verify(api, never()).publishText(anyString());
    }
    @Test void preflightFailureIsDefinitivelyBeforeSend() {
        when(api.getMe()).thenThrow(new IllegalStateException("mock IO"));
        assertThrows(XPublishingException.class, () -> publisher.publish(claimed));
        verify(api, never()).publishText(anyString());
    }
    @Test void expiryDuringAccountCheckStopsPost() {
        when(api.getMe()).thenAnswer(invocation -> { time.set(claimed.expiresAt()); return new XUser("123", "test", "test"); });
        assertThrows(XPublishingException.class, () -> publisher.publish(claimed));
        verify(api, never()).publishText(anyString());
    }
    @Test void missingCredentialsAndLiveGateFailClosed() {
        client.setAccessTokenSecret(""); assertFalse(publisher.credentialsAvailable("123"));
        assertThrows(XPublishingException.class, () -> publisher.publish(claimed));
        client.setAccessTokenSecret("fake"); settings.setLivePublishingEnabled(false);
        assertThrows(XPublishingException.class, () -> publisher.publish(claimed)); verifyNoInteractions(api);
    }
    @Test void pendingOrUnclaimedApprovalCannotBypassReview() {
        assertThrows(XPublishingException.class, () -> publisher.publish(pending));
        var approved = pending.review(1, true, "telegram:42", "approved", now, now);
        assertThrows(XPublishingException.class, () -> publisher.publish(approved)); verifyNoInteractions(api);
    }
    @Test void uncertainCreatePostIsPropagatedWithoutRetryOrPresendMisclassification() {
        when(api.publishText(claimed.body())).thenThrow(new IllegalStateException("mock timeout"));
        var failure = assertThrows(IllegalStateException.class, () -> publisher.publish(claimed));
        assertEquals("mock timeout", failure.getMessage());
        verify(api, times(1)).publishText(claimed.body());
    }
}
