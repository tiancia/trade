package com.trade.weibo.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboPublishResult;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.weibo.infrastructure.config.WeiboWorkflowProperties;
import com.trade.weibo.domain.model.*;
import com.trade.weibo.port.WeiboAccountTokenRepository;
import com.trade.weibo.domain.exception.WeiboPublishingException;
import com.trade.weibo.domain.model.WeiboAccountToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeiboPublishingServiceTest {
    private final Instant now = Instant.parse("2026-06-16T15:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @ParameterizedTest
    @ValueSource(strings = {"module", "publishing", "target", "live"})
    void changedGatesDuringTokenLookupNeverPublish(String change) {
        var api = mock(WeiboApi.class);
        when(api.publishText("token", "正文")).thenReturn(new WeiboPublishResult("123", null, null, null));
        var tokens = mock(WeiboAccountTokenRepository.class);
        var properties = new WeiboClientProperties();
        properties.setLivePublishingEnabled(true);
        var workflow = enabledWorkflow();
        Instant started = Instant.now();
        var claimed = claimed(workflow, started);
        when(tokens.findValidForUid(org.mockito.ArgumentMatchers.eq("uid"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    switch (change) {
                        case "module" -> workflow.setEnabled(false);
                        case "publishing" -> workflow.setPublishingEnabled(false);
                        case "target" -> workflow.setTargetUid("other");
                        case "live" -> properties.setLivePublishingEnabled(false);
                        default -> throw new AssertionError(change);
                    }
                    return Optional.of(new WeiboAccountToken("uid", "token", started.plusSeconds(3600)));
                });
        var service = new WeiboPublishingService(api, tokens, properties, workflow);

        assertThrows(WeiboPublishingException.class, () -> service.publish(claimed));
        verify(api, never()).publishText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tokenLookupFailureIsKnownToBeBeforeSend() {
        var api = mock(WeiboApi.class);
        var tokens = mock(WeiboAccountTokenRepository.class);
        var properties = new WeiboClientProperties();
        properties.setLivePublishingEnabled(true);
        var workflow = enabledWorkflow();
        var claimed = claimed(workflow, now);
        when(tokens.findValidForUid(org.mockito.ArgumentMatchers.eq("uid"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        var service = new WeiboPublishingService(api, tokens, clock, properties, workflow.policy());

        assertThrows(WeiboPublishingException.class, () -> service.publish(claimed));
        verify(api, never()).publishText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enablingReviewedWorkflowDuringLegacyTokenLookupBlocksDirectPublishing() {
        var api = mock(WeiboApi.class);
        var tokens = mock(WeiboAccountTokenRepository.class);
        var properties = new WeiboClientProperties();
        properties.setLivePublishingEnabled(true);
        properties.setReviewRequired(false);
        var workflow = new WeiboWorkflowProperties();
        workflow.setTargetUid("uid");
        when(tokens.findValid(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            workflow.setEnabled(true);
            return Optional.of(new WeiboAccountToken("uid", "token", Instant.now().plusSeconds(3600)));
        });
        var service = new WeiboPublishingService(api, tokens, properties, workflow);

        assertThrows(WeiboPublishingException.class, () -> service.publishText("hello"));
        verify(api, never()).publishText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void draftExpiringDuringTokenLookupNeverPublishes() {
        var api = mock(WeiboApi.class);
        var tokens = mock(WeiboAccountTokenRepository.class);
        var properties = new WeiboClientProperties();
        properties.setLivePublishingEnabled(true);
        var workflow = enabledWorkflow();
        var claimed = claimed(workflow, now);
        var advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(now);
        when(tokens.findValidForUid(org.mockito.ArgumentMatchers.eq("uid"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    when(advancingClock.instant()).thenReturn(claimed.expiresAt());
                    return Optional.of(new WeiboAccountToken("uid", "token", claimed.expiresAt().plusSeconds(3600)));
                });
        var service = new WeiboPublishingService(api, tokens, advancingClock, properties, workflow.policy());

        assertThrows(WeiboPublishingException.class, () -> service.publish(claimed));
        verify(api, never()).publishText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sendFailureRemainsUncertainAndIsNeverRetried() {
        var api = mock(WeiboApi.class);
        var properties = new WeiboClientProperties();
        properties.setLivePublishingEnabled(true);
        var workflow = enabledWorkflow();
        var claimed = claimed(workflow, now);
        var service = new WeiboPublishingService(api, new OneTokenRepository(), clock, properties, workflow.policy());
        var timeout = new IllegalStateException("timeout after send");
        when(api.publishText("token", "正文")).thenThrow(timeout);

        org.junit.jupiter.api.Assertions.assertSame(timeout,
                assertThrows(IllegalStateException.class, () -> service.publish(claimed)));
        verify(api, org.mockito.Mockito.times(1)).publishText("token", "正文");
    }

    private static WeiboWorkflowProperties enabledWorkflow() {
        var workflow = new WeiboWorkflowProperties();
        workflow.setEnabled(true);
        workflow.setPublishingEnabled(true);
        workflow.setTargetUid("uid");
        return workflow;
    }

    private static WeiboPost claimed(WeiboWorkflowProperties workflow, Instant createdAt) {
        return WeiboPost.generating("uid", new HotEvent("事件", "https://example.test/news",
                "事实", createdAt, createdAt), createdAt, workflow.policy())
                .generated(new GeneratedComment("正文", "说明"), createdAt, 280)
                .review(1, true, "reviewer", null, createdAt, createdAt).claim(createdAt);
    }

    @Test
    void rejectsBlankStatus() {
        WeiboApi api = mock(WeiboApi.class);
        WeiboPublishingService service = service(api, new EmptyTokenRepository());

        assertThrows(IllegalArgumentException.class, () -> service.publishText("   "));

        verify(api, never()).publishText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsWhenNoValidTokenExists() {
        WeiboApi api = mock(WeiboApi.class);
        WeiboPublishingService service = service(api, new EmptyTokenRepository());

        assertThrows(WeiboPublishingException.class, () -> service.publishText("hello"));

        verify(api, never()).publishText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishesWithCurrentValidToken() throws Exception {
        WeiboApi api = mock(WeiboApi.class);
        WeiboPublishResult result = new WeiboPublishResult(
                "123",
                "456",
                "Tue Jun 16 23:00:00 +0800 2026",
                new ObjectMapper().readTree("{\"id\":123,\"mid\":\"456\"}")
        );
        when(api.publishText("token", "hello")).thenReturn(result);
        WeiboPublishingService service = service(api, new OneTokenRepository());

        assertEquals(result, service.publishText(" hello "));

        verify(api).publishText("token", "hello");
    }

    private static final class EmptyTokenRepository implements WeiboAccountTokenRepository {
        @Override
        public void upsert(String uid, String accessToken, Instant expiresAt) {
        }

        @Override
        public Optional<WeiboAccountToken> findCurrent() {
            return Optional.empty();
        }

        @Override
        public Optional<WeiboAccountToken> findValid(Instant now) {
            return Optional.empty();
        }
    }

    @Test
    void defaultSettingsBlockRealPublishingAndDirectReviewBypass() {
        WeiboApi api = mock(WeiboApi.class);
        WeiboClientProperties properties = new WeiboClientProperties();
        WeiboPublishingService service = new WeiboPublishingService(api, new OneTokenRepository(), clock,
                properties, new WeiboWorkflowProperties().policy());
        assertThrows(WeiboPublishingException.class, () -> service.publishText("hello"));
        properties.setLivePublishingEnabled(true);
        assertThrows(WeiboPublishingException.class, () -> service.publishText("hello"));
        verify(api, never()).publishText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void workflowCannotBeBypassedEvenIfLegacyReviewRequirementIsTurnedOff() {
        WeiboApi api = mock(WeiboApi.class);
        WeiboClientProperties properties = new WeiboClientProperties();
        properties.setLivePublishingEnabled(true);
        properties.setReviewRequired(false);
        WeiboWorkflowProperties workflow = new WeiboWorkflowProperties();
        workflow.setEnabled(true);
        WeiboPublishingService service = new WeiboPublishingService(api, new OneTokenRepository(), clock,
                properties, workflow.policy());
        assertThrows(WeiboPublishingException.class, () -> service.publishText("hello"));
        verify(api, never()).publishText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unrelatedValidAccountCannotReplaceTheBoundAccount() {
        WeiboApi api = mock(WeiboApi.class);
        WeiboPublishingService service = service(api, new OneTokenRepository());
        org.junit.jupiter.api.Assertions.assertFalse(service.credentialsAvailable("different-uid"));
    }

    private WeiboPublishingService service(WeiboApi api, WeiboAccountTokenRepository repository) {
        WeiboClientProperties properties = new WeiboClientProperties();
        properties.setLivePublishingEnabled(true);
        properties.setReviewRequired(false);
        return new WeiboPublishingService(api, repository, clock, properties, new WeiboWorkflowProperties().policy());
    }

    @Test
    void reviewedPublishingRechecksAccountStatusAndBothLiveGates() throws Exception {
        WeiboApi api = mock(WeiboApi.class);
        when(api.publishText("token", "正文")).thenReturn(new WeiboPublishResult("123", null, null,
                new ObjectMapper().readTree("{\"id\":123}")));
        WeiboClientProperties properties = new WeiboClientProperties();
        WeiboWorkflowProperties workflow = new WeiboWorkflowProperties();
        workflow.setEnabled(true);
        workflow.setPublishingEnabled(true);
        workflow.setTargetUid("uid");
        WeiboPost pending = WeiboPost.generating("uid", new HotEvent("事件", "https://example.test/news",
                "事实", now, now), now, workflow.policy()).generated(new GeneratedComment("正文", "说明"), now, 280);
        WeiboPost claimed = pending.review(1, true, "reviewer", null, now, now).claim(now);
        WeiboPublishingService service = new WeiboPublishingService(api, new OneTokenRepository(), clock,
                properties, workflow.policy());
        assertThrows(WeiboPublishingException.class, () -> service.publish(claimed));
        properties.setLivePublishingEnabled(true);
        assertThrows(WeiboPublishingException.class, () -> service.publish(pending));
        assertEquals("123", service.publish(claimed));
        workflow.setPublishingEnabled(false);
        WeiboPublishingService disabled = new WeiboPublishingService(api, new OneTokenRepository(), clock,
                properties, workflow.policy());
        assertThrows(WeiboPublishingException.class, () -> disabled.publish(claimed));
        verify(api, org.mockito.Mockito.times(1)).publishText("token", "正文");
    }

    private static final class OneTokenRepository implements WeiboAccountTokenRepository {
        @Override
        public void upsert(String uid, String accessToken, Instant expiresAt) {
        }

        @Override
        public Optional<WeiboAccountToken> findCurrent() {
            return Optional.of(new WeiboAccountToken("uid", "token", Instant.parse("2026-06-16T16:00:00Z")));
        }

        @Override
        public Optional<WeiboAccountToken> findValid(Instant now) {
            return findCurrent();
        }
    }
}
