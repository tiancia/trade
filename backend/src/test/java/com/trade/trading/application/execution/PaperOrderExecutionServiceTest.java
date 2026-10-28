package com.trade.trading.application.execution;

import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.port.PaperAccountStore;
import com.trade.trading.domain.model.OrderSizing;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.domain.paper.PaperFill;
import com.trade.trading.domain.risk.RiskAssessment;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class PaperOrderExecutionServiceTest {
    @Test void secondaryRiskWriteFailureCannotEraseCommittedFillStatusOrSubmitAgain() {
        TradingProperties props = new TradingProperties();
        var accounts = mock(PaperAccountService.class);
        var risk = mock(RiskControlService.class);
        var sizing = mock(OrderSizingService.class);
        var executor = new PaperOrderExecutionService(sizing, props, risk, accounts);
        var context = new TradingDecisionContext().setPaperSessionId("session").setPaperSessionVersion(1L);
        var record = new TradingDecisionRecord().setDecisionId("once").setMetadata(Map.of("paperSessionId", "session"));
        var decision = new StrategyDecision().setAction(TradingAction.BUY);
        when(risk.evaluate(decision, context)).thenReturn(new RiskAssessment().setAllowed(true));
        when(sizing.buySize(decision, context)).thenReturn(OrderSizing.executable("10"));
        var fill = new PaperFill(1L, "session", "once", "threshold", 1L, "{}", "BUY", BigDecimal.TEN,
                BigDecimal.ONE, new BigDecimal("0.999"), BigDecimal.TEN, BigDecimal.TEN.negate(),
                new BigDecimal("0.01"), BigDecimal.ZERO, new BigDecimal("90"), new BigDecimal("0.999"),
                new BigDecimal("99.99"), 1, Instant.now());
        when(accounts.fill(context, record, "BUY", BigDecimal.TEN))
                .thenReturn(new PaperAccountStore.AppliedFill(fill, null, false));
        doThrow(new IllegalStateException("database unavailable after fill commit")).when(risk).recordExecutedAction(decision, context);
        executor.execute(decision, context, record);
        assertEquals("PAPER_FILLED", record.getExecutionStatus());
        assertEquals("10", record.getOrderSize());
        assertTrue(record.getMetadata().get("paperRiskUpdateWarning").toString().contains("fill committed"));
        verify(accounts, times(1)).fill(context, record, "BUY", BigDecimal.TEN);
        verify(risk, times(1)).recordExecutedAction(decision, context);
    }

    @Test void noImplicitCashSeedAndNoDerivativeFakeFill() {
        TradingProperties props = new TradingProperties();
        var accounts = mock(PaperAccountService.class);
        var risk = mock(RiskControlService.class);
        var sizing = mock(OrderSizingService.class);
        var executor = new PaperOrderExecutionService(sizing, props, risk, accounts);
        var record = new TradingDecisionRecord();
        executor.execute(new StrategyDecision().setAction(TradingAction.BUY), new TradingDecisionContext(), record);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertEquals("Initialize simulated funds before trading", record.getSkipReason());
        props.setInstType("SWAP");
        executor.execute(new StrategyDecision().setAction(TradingAction.OPEN_LONG),
                new TradingDecisionContext().setPaperSessionId("session"), record);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertEquals("Funded simulation currently supports spot BUY/SELL only", record.getSkipReason());
        verifyNoInteractions(accounts, risk, sizing);
    }

    @Test void replayReportsOriginalLedgerSizeAndDoesNotRepeatExecutedRiskTransition() {
        TradingProperties props = new TradingProperties();
        var accounts = mock(PaperAccountService.class);
        var risk = mock(RiskControlService.class);
        var sizing = mock(OrderSizingService.class);
        var executor = new PaperOrderExecutionService(sizing, props, risk, accounts);
        var context = new TradingDecisionContext().setPaperSessionId("session").setPaperSessionVersion(1L);
        var record = new TradingDecisionRecord().setDecisionId("signal");
        var decision = new StrategyDecision().setAction(TradingAction.BUY);
        when(risk.evaluate(decision, context)).thenReturn(new RiskAssessment().setAllowed(true));
        when(sizing.buySize(decision, context)).thenReturn(OrderSizing.executable("20"));
        var original = new PaperFill(1L, "session", "signal", "intraday", 1L, "{}", "BUY", BigDecimal.TEN,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN.negate(), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("90"), BigDecimal.ONE, new BigDecimal("100"), 1, Instant.now());
        when(accounts.fill(context, record, "BUY", new BigDecimal("20")))
                .thenReturn(new PaperAccountStore.AppliedFill(original, null, true));

        executor.execute(decision, context, record);

        assertEquals("PAPER_FILLED", record.getExecutionStatus());
        assertEquals("10", record.getOrderSize());
        assertTrue(record.isIdempotentReplay());
        verify(risk, never()).recordExecutedAction(any(), any());
    }
}
