package com.trade.automation.interfaces.web;

import com.trade.automation.application.task.ModuleControlService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModuleControlControllerTest {
    @Test void missingEnabledCannotSilentlyDisableAModule() throws Exception {
        var service = mock(ModuleControlService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ModuleControlController(service)).build();
        for (String body : new String[]{"{}", "{\"enabled\":null}"}) {
            mvc.perform(put("/api/automation/modules/story").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
        mvc.perform(put("/api/automation/modules/story").contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        verify(service).setEnabled("story", false);
    }
}
