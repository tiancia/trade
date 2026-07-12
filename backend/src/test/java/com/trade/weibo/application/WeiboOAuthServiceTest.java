package com.trade.weibo.application;

import com.trade.client.weibo.WeiboAccessToken;
import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.weibo.application.port.WeiboAccountTokenRepository;
import com.trade.weibo.application.port.WeiboOAuthStateRepository;
import com.trade.weibo.model.WeiboAccountToken;
import com.trade.weibo.model.WeiboAuthorization;
import com.trade.weibo.model.WeiboAuthorizeUrl;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeiboOAuthServiceTest {
    private final Instant now = Instant.parse("2026-06-16T15:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void createsStateAndAuthorizeUrl() {
        WeiboApi api = mock(WeiboApi.class);
        when(api.buildAuthorizeUrl(any())).thenReturn("https://api.weibo.com/oauth2/authorize?state=test");
        InMemoryOAuthStateRepository stateRepository = new InMemoryOAuthStateRepository();

        WeiboOAuthService service = new WeiboOAuthService(api, stateRepository, new InMemoryAccountTokenRepository(), clock);

        WeiboAuthorizeUrl authorization = service.createAuthorizeUrl();

        assertEquals("https://api.weibo.com/oauth2/authorize?state=test", authorization.authorizeUrl());
        assertFalse(authorization.state().isBlank());
        assertEquals(now.plusSeconds(600), authorization.expiresAt());
        assertEquals(authorization.expiresAt(), stateRepository.expiresAt);
        verify(api).buildAuthorizeUrl(authorization.state());
    }

    @Test
    void rejectsExpiredOrUsedStateBeforeCallingWeibo() {
        WeiboApi api = mock(WeiboApi.class);
        InMemoryOAuthStateRepository stateRepository = new InMemoryOAuthStateRepository();
        stateRepository.consumeResult = false;
        WeiboOAuthService service = new WeiboOAuthService(api, stateRepository, new InMemoryAccountTokenRepository(), clock);

        assertThrows(WeiboOAuthException.class, () -> service.handleCallback("code", "state"));

        verify(api, never()).exchangeCode(any());
    }

    @Test
    void exchangesCodeFetchesUidAndStoresToken() {
        WeiboApi api = mock(WeiboApi.class);
        when(api.exchangeCode("code")).thenReturn(new WeiboAccessToken("access-token", 3600));
        when(api.getUid("access-token")).thenReturn("12345");
        InMemoryOAuthStateRepository stateRepository = new InMemoryOAuthStateRepository();
        InMemoryAccountTokenRepository tokenRepository = new InMemoryAccountTokenRepository();
        WeiboOAuthService service = new WeiboOAuthService(api, stateRepository, tokenRepository, clock);

        WeiboAuthorization authorization = service.handleCallback("code", "state");

        assertTrue(authorization.authorized());
        assertEquals("12345", authorization.uid());
        assertEquals(now.plusSeconds(3600), authorization.expiresAt());
        assertEquals("12345", tokenRepository.uid);
        assertEquals("access-token", tokenRepository.accessToken);
        assertEquals(now.plusSeconds(3600), tokenRepository.expiresAt);
    }

    private static final class InMemoryOAuthStateRepository implements WeiboOAuthStateRepository {
        private boolean consumeResult = true;
        private String state;
        private Instant expiresAt;

        @Override
        public void save(String state, Instant expiresAt) {
            this.state = state;
            this.expiresAt = expiresAt;
        }

        @Override
        public boolean consume(String state, Instant usedAt) {
            this.state = state;
            return consumeResult;
        }
    }

    private static final class InMemoryAccountTokenRepository implements WeiboAccountTokenRepository {
        private String uid;
        private String accessToken;
        private Instant expiresAt;

        @Override
        public void upsert(String uid, String accessToken, Instant expiresAt) {
            this.uid = uid;
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
        }

        @Override
        public java.util.Optional<WeiboAccountToken> findCurrent() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<WeiboAccountToken> findValid(Instant now) {
            return java.util.Optional.empty();
        }
    }
}
