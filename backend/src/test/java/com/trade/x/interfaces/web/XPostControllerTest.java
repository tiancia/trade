package com.trade.x.interfaces.web;

import com.trade.x.application.service.XPostService;
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
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new XPostController(posts)).build();
    }

    @Test void generateWithoutTokenReturnsConflictWhenQuotaAlreadyReserved() throws Exception {
        when(posts.generate()).thenReturn(Optional.empty());
        mvc.perform(post("/api/x/posts")).andExpect(status().isConflict());
        verify(posts).generate();
    }
    @Test void readAndEditingWithoutTokenHonorRevisionFence() throws Exception {
        when(posts.recent(30)).thenReturn(List.of());
        mvc.perform(get("/api/x/posts")).andExpect(status().isOk())
                .andExpect(content().json("[]"));
        when(posts.revise("id", 2, "draft")).thenThrow(new IllegalStateException("Post changed; reload before editing"));
        mvc.perform(put("/api/x/posts/id")
                .contentType("application/json").content("{\"expectedRevision\":2,\"body\":\"draft\"}"))
                .andExpect(status().isConflict());
        verify(posts).revise("id", 2, "draft");
    }
    @Test void detailAndHistoryCanBeReadWithoutToken() throws Exception {
        when(posts.history("id")).thenReturn(List.of());
        mvc.perform(get("/api/x/posts/id")).andExpect(status().isOk());
        mvc.perform(get("/api/x/posts/id/history")).andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(posts).get("id");
        verify(posts).history("id");
    }
    @Test void controllerIsRegisteredWithoutTokenConfiguration() {
        var runner = new ApplicationContextRunner().withUserConfiguration(XPostController.class)
                .withBean(XPostService.class, () -> posts);
        runner.run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(XPostController.class));
        verifyNoInteractions(posts);
    }
}
