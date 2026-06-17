package com.trade.weibo.web;

import com.trade.client.weibo.WeiboClientProperties;
import com.trade.weibo.WeiboAccount;
import com.trade.weibo.WeiboAccountService;
import com.trade.weibo.WeiboAuthorizeUrl;
import com.trade.weibo.WeiboOAuthService;
import com.trade.weibo.WeiboPublishingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeiboControllerTest {
    @Test
    void rejectsMissingOrInvalidAdminToken() {
        WeiboAccountService accountService = mock(WeiboAccountService.class);
        WeiboController controller = controller(accountService);

        assertThrows(WeiboUnauthorizedException.class, () -> controller.account(null));
        assertThrows(WeiboUnauthorizedException.class, () -> controller.account("wrong"));
    }

    @Test
    void returnsAuthorizeUrlWithValidAdminToken() {
        WeiboOAuthService oauthService = mock(WeiboOAuthService.class);
        WeiboClientProperties properties = properties();
        WeiboController controller = new WeiboController(
                oauthService,
                mock(WeiboAccountService.class),
                mock(WeiboPublishingService.class),
                properties
        );
        WeiboAuthorizeUrl expected = new WeiboAuthorizeUrl(
                "https://api.weibo.com/oauth2/authorize?state=abc",
                "abc",
                Instant.parse("2026-06-16T15:10:00Z")
        );
        when(oauthService.createAuthorizeUrl()).thenReturn(expected);

        assertEquals(expected, controller.authorizeUrl("admin-token"));
    }

    @Test
    void returnsCurrentAccountWithValidAdminToken() {
        WeiboAccountService accountService = mock(WeiboAccountService.class);
        WeiboAccount expected = new WeiboAccount("12345", true, Instant.parse("2026-06-16T16:00:00Z"));
        when(accountService.currentAccount()).thenReturn(expected);
        WeiboController controller = controller(accountService);

        assertEquals(expected, controller.account("admin-token"));

        verify(accountService).currentAccount();
    }

    private static WeiboController controller(WeiboAccountService accountService) {
        return new WeiboController(
                mock(WeiboOAuthService.class),
                accountService,
                mock(WeiboPublishingService.class),
                properties()
        );
    }

    private static WeiboClientProperties properties() {
        WeiboClientProperties properties = new WeiboClientProperties();
        properties.setAdminToken("admin-token");
        return properties;
    }
}
