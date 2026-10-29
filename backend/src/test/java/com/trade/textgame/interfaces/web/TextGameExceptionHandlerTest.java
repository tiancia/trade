package com.trade.textgame.interfaces.web;

import com.trade.textgame.application.service.TextGameAdminService;
import com.trade.textgame.application.service.TextGameSessionService;
import com.trade.textgame.domain.exception.TextGameConflictException;
import com.trade.textgame.domain.exception.TextGameNotFoundException;
import com.trade.textgame.infrastructure.config.TextGameProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TextGameExceptionHandlerTest {
    private final TextGameSessionService sessions = mock(TextGameSessionService.class);
    private final TextGameAdminService admin = mock(TextGameAdminService.class);

    @ParameterizedTest
    @MethodSource("errors")
    void publicAndAdminApisRetainStatusAndErrorBody(RuntimeException failure, int status) throws Exception {
        when(sessions.getSession("trial")).thenThrow(failure);
        when(admin.getVersion("story", 1)).thenThrow(failure);
        MockMvc mvc = mvc();
        mvc.perform(get("/api/text-game/sessions/trial"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.error").value(failure.getMessage()));
        mvc.perform(get("/api/text-game/admin/stories/story/versions/1").header("X-Admin-Token", "test-token"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.error").value(failure.getMessage()));
    }

    @Test
    void adminAuthorizationStillPrecedesServiceCalls() throws Exception {
        mvc().perform(get("/api/text-game/admin/stories").header("X-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("管理令牌无效"));
        verifyNoInteractions(admin);
    }

    private static Stream<Arguments> errors() {
        return Stream.of(Arguments.of(new IllegalArgumentException("invalid"), 400),
                Arguments.of(new TextGameNotFoundException("missing"), 404),
                Arguments.of(new TextGameConflictException("changed"), 409));
    }

    private MockMvc mvc() {
        TextGameProperties properties = new TextGameProperties();
        properties.setAdminToken("test-token");
        return MockMvcBuilders.standaloneSetup(new TextGameController(sessions), new TextGameAdminController(admin, properties))
                .setControllerAdvice(new TextGameExceptionHandler()).build();
    }
}
