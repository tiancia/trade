package com.trade.trading.infrastructure.broker;

import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TradingBrokerRouterTest {
    @Test
    void routesOnlyExplicitRealtimeModes() {
        TradingProperties properties = new TradingProperties();
        PaperBroker paper = mock(PaperBroker.class);
        OkxLiveBroker live = mock(OkxLiveBroker.class);
        TradingBrokerRouter router = new TradingBrokerRouter(properties, paper, live);
        StrategyDecision decision = new StrategyDecision();
        TradingDecisionRecord record = new TradingDecisionRecord();

        router.execute(decision, null, record);
        verify(paper).execute(decision, null, record);
        verifyNoInteractions(live);

        properties.setExecutionMode(ExecutionMode.LIVE);
        router.execute(decision, null, record);
        verify(live).execute(decision, null, record);
        verifyNoMoreInteractions(paper, live);
    }

    @Test
    void backtestAndMissingModeCannotMutatePaperOrLiveAccounts() {
        TradingProperties properties = new TradingProperties();
        PaperBroker paper = mock(PaperBroker.class);
        OkxLiveBroker live = mock(OkxLiveBroker.class);
        TradingBrokerRouter router = new TradingBrokerRouter(properties, paper, live);
        for (ExecutionMode mode : new ExecutionMode[]{ExecutionMode.BACKTEST, null}) {
            properties.setExecutionMode(mode);
            TradingDecisionRecord record = new TradingDecisionRecord();
            router.execute(new StrategyDecision(), null, record);
            assertEquals("SKIPPED", record.getExecutionStatus());
        }
        verifyNoInteractions(paper, live);
    }
}
