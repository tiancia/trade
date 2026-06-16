package com.trade.textgame.web;

import com.trade.textgame.application.TextGameAdminService;
import com.trade.textgame.config.TextGameProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TextGameAdminControllerTest {
    @Test
    void rejectsInvalidTokenBeforeCallingService() {
        TextGameAdminService service = mock(TextGameAdminService.class);
        TextGameProperties properties = new TextGameProperties();
        properties.setAdminToken("expected-token");
        TextGameAdminController controller = new TextGameAdminController(service, properties);

        assertThrows(RuntimeException.class, () -> controller.stories("wrong-token"));
        verifyNoInteractions(service);
    }

    @Test
    void acceptsConfiguredToken() {
        TextGameAdminService service = mock(TextGameAdminService.class);
        when(service.listStories()).thenReturn(List.of());
        TextGameProperties properties = new TextGameProperties();
        properties.setAdminToken("expected-token");
        TextGameAdminController controller = new TextGameAdminController(service, properties);

        assertEquals(List.of(), controller.stories("expected-token"));
    }
}
