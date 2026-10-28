package com.trade.trading.interfaces.web;

import com.trade.trading.application.strategy.TradingAiAdvisor;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.infrastructure.config.TradingAiProperties;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TradingAiControllerTest {
    @Test void statusNeverExposesCredentialsAndLiveCannotAutoApply() throws Exception {
        var ai = new TradingAiProperties(); ai.setEnabled(true); ai.setAutoApply(true);
        ai.getTelegram().getClient().setBotToken("222:private-test-value");
        var trading = new TradingProperties(); trading.setExecutionMode(ExecutionMode.LIVE);
        var advisor = mock(TradingAiAdvisor.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TradingAiController(advisor, ai, trading)).build();
        mvc.perform(get("/api/trading/ai/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.autoApplyEligible").value(false))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-test-value"))));
        when(advisor.recent(20)).thenReturn(List.of());
        mvc.perform(get("/api/trading/ai/reviews")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/trading/ai/reviews?limit=101")).andExpect(status().isBadRequest());
        verify(advisor, times(1)).recent(20);
        verifyNoMoreInteractions(advisor);
    }
}
