package com.trade.x.interfaces.web;

import com.trade.x.application.service.XPostService;
import com.trade.x.infrastructure.config.XPublishingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class XPostControllerTest {
    private final XPostService posts = mock(XPostService.class);
    private final XPublishingProperties settings = new XPublishingProperties();
    private MockMvc mvc;
    @BeforeEach void setup() {
        settings.setAdminToken("offline-admin");
        mvc = MockMvcBuilders.standaloneSetup(new XPostController(posts, settings)).build();
    }

    @Test void absentOrWrongHeaderNeverCallsBusinessService() throws Exception {
        mvc.perform(post("/api/x/posts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/x/posts").header("X-X-Admin-Token", "wrong")).andExpect(status().isUnauthorized());
        verifyNoInteractions(posts);
    }
    @Test void authorizedGenerateReturnsConflictWhenQuotaAlreadyReserved() throws Exception {
        when(posts.generate()).thenReturn(Optional.empty());
        mvc.perform(post("/api/x/posts").header("X-X-Admin-Token", "offline-admin")).andExpect(status().isConflict());
        verify(posts).generate();
    }
    @Test void authorizedReadAndEditingHonorRevisionFence() throws Exception {
        when(posts.recent(30)).thenReturn(List.of());
        mvc.perform(get("/api/x/posts").header("X-X-Admin-Token", "offline-admin")).andExpect(status().isOk())
                .andExpect(content().json("[]"));
        when(posts.revise("id", 2, "draft")).thenThrow(new IllegalStateException("Post changed; reload before editing"));
        mvc.perform(put("/api/x/posts/id").header("X-X-Admin-Token", "offline-admin")
                .contentType("application/json").content("{\"expectedRevision\":2,\"body\":\"draft\"}"))
                .andExpect(status().isConflict());
        verify(posts).revise("id", 2, "draft");
    }
    @Test void controllerIsAbsentWithoutTokenAndQuotesAreSafeToConfigure() {
        var runner = new ApplicationContextRunner().withUserConfiguration(XPostController.class)
                .withBean(XPostService.class, () -> posts).withBean(XPublishingProperties.class, () -> settings);
        runner.run(context -> assertThat(context).doesNotHaveBean(XPostController.class));
        settings.setAdminToken("offline'quoted-token");
        runner.withPropertyValues("trade.x.admin-token=offline'quoted-token").run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(XPostController.class));
        verifyNoInteractions(posts);
    }
}
