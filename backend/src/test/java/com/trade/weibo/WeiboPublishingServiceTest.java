package com.trade.weibo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboPublishResult;
import org.junit.jupiter.api.Test;

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

    @Test
    void rejectsBlankStatus() {
        WeiboApi api = mock(WeiboApi.class);
        WeiboPublishingService service = new WeiboPublishingService(api, new EmptyTokenRepository(), clock);

        assertThrows(IllegalArgumentException.class, () -> service.publishText("   "));

        verify(api, never()).publishText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsWhenNoValidTokenExists() {
        WeiboApi api = mock(WeiboApi.class);
        WeiboPublishingService service = new WeiboPublishingService(api, new EmptyTokenRepository(), clock);

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
        WeiboPublishingService service = new WeiboPublishingService(api, new OneTokenRepository(), clock);

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
