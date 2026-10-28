package com.trade.trading.interfaces.web;

import com.trade.trading.application.paper.PaperAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.NoSuchElementException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaperAccountControllerTest {
    @Test void deletesVersionedSessionsAndMapsDeletionFailures() throws Exception {
        var service = mock(PaperAccountService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PaperAccountController(service)).build();
        mvc.perform(delete("/api/trading/paper/sessions/trial?expectedVersion=2"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).delete("trial", 2L);
        mvc.perform(delete("/api/trading/paper/sessions/trial")).andExpect(status().isBadRequest());
        doThrow(new IllegalArgumentException("Invalid version")).when(service).delete("trial", -1L);
        mvc.perform(delete("/api/trading/paper/sessions/trial?expectedVersion=-1")).andExpect(status().isBadRequest());
        doThrow(new ConcurrentModificationException("changed")).when(service).delete("trial", 1L);
        mvc.perform(delete("/api/trading/paper/sessions/trial?expectedVersion=1")).andExpect(status().isConflict());
        doThrow(new NoSuchElementException("not found")).when(service).delete("missing", 0L);
        mvc.perform(delete("/api/trading/paper/sessions/missing?expectedVersion=0")).andExpect(status().isNotFound());
    }

    @Test void missingAccountReturnsJsonNullAndSessionHistoryIsBounded() throws Exception {
        var service = mock(PaperAccountService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PaperAccountController(service)).build();
        mvc.perform(get("/api/trading/paper/account")).andExpect(status().isOk()).andExpect(content().string("null"));
        when(service.sessions(null, 20)).thenReturn(List.of());
        mvc.perform(get("/api/trading/paper/sessions")).andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).sessions(null, 20);
        when(service.equity("trial", 17L, 3)).thenReturn(List.of());
        mvc.perform(get("/api/trading/paper/sessions/trial/equity?limit=3&beforeId=17"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).equity("trial", 17L, 3);
    }

    @Test void mapsInvalidInitializationRevisionConflictAndMissingHistory() throws Exception {
        var service = mock(PaperAccountService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PaperAccountController(service)).build();
        when(service.start(new BigDecimal("-1"), null, null)).thenThrow(new IllegalArgumentException("Initial funds must be positive"));
        mvc.perform(post("/api/trading/paper/sessions").contentType("application/json").content("{\"initialCash\":-1}"))
                .andExpect(status().isBadRequest());
        when(service.start(new BigDecimal("100"), "old", 2L)).thenThrow(new ConcurrentModificationException("changed"));
        mvc.perform(post("/api/trading/paper/sessions").contentType("application/json")
                .content("{\"initialCash\":100,\"expectedSessionId\":\"old\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict());
        when(service.session("missing")).thenThrow(new NoSuchElementException("not found"));
        mvc.perform(get("/api/trading/paper/sessions/missing")).andExpect(status().isNotFound());
        when(service.sessions(null, 501)).thenThrow(new IllegalArgumentException("Limit must be between 1 and 500"));
        mvc.perform(get("/api/trading/paper/sessions?limit=501")).andExpect(status().isBadRequest());
    }
}
