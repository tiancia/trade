package com.trade.weibo.interfaces.web;

import com.trade.client.weibo.WeiboClientProperties;
import com.trade.weibo.application.service.WeiboAccountService;
import com.trade.weibo.application.service.WeiboOAuthService;
import com.trade.weibo.application.service.WeiboPublishingService;
import com.trade.weibo.application.service.WeiboPostService;
import com.trade.weibo.domain.model.WeiboAccount;
import com.trade.weibo.domain.model.WeiboAuthorizeUrl;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WeiboControllerTest {
    @Test
    void everyNewHttpEndpointRequiresAdminAndHasNoApprovalShortcut() throws Exception {
        WeiboPostService posts = mock(WeiboPostService.class);
        WeiboController controller = new WeiboController(mock(WeiboOAuthService.class),
                mock(WeiboAccountService.class), mock(WeiboPublishingService.class), posts, properties());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                .build();
        mvc.perform(get("/api/weibo/posts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/weibo/posts/id")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/weibo/posts/id/history")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/weibo/posts").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/weibo/posts/id").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        when(posts.recent(30)).thenReturn(List.of());
        mvc.perform(get("/api/weibo/posts").header("X-Weibo-Admin-Token", "admin-token"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post("/api/weibo/posts/id/approve").header("X-Weibo-Admin-Token", "admin-token"))
                .andExpect(status().isNotFound());
        verify(posts, never()).generate(any());
        verify(posts, never()).revise(anyString(), anyLong(), anyString());
    }
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
                mock(WeiboPostService.class),
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
                mock(WeiboPostService.class),
                properties()
        );
    }

    private static WeiboClientProperties properties() {
        WeiboClientProperties properties = new WeiboClientProperties();
        properties.setAdminToken("admin-token");
        return properties;
    }
}
