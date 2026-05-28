package com.trade.trading.execution;

import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.risk.RiskControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperBrokerTest {
    @TempDir
    Path tempDir;

    @Test
    void paperBuyFillsWithoutCallingOkxAndUpdatesLocalState() {
        TradingProperties properties = new TradingProperties();
        properties.getRisk().setEnabled(false);
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("paper-state.json"));
        PaperBroker broker = new PaperBroker(
                new OrderSizingService(properties),
                stateRepository,
                properties,
                new RiskControlService(properties, stateRepository)
        );
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker.execute(new StrategyDecision()
                        .setStrategyId("paper")
                        .setAction(TradingAction.BUY)
                        .setReason("paper buy")
                        .setBuyQuoteAmount(new BigDecimal("10")),
                context(),
                record);

        assertEquals("PAPER_FILLED", record.getExecutionStatus());
        assertEquals(1, stateRepository.getState().getTrackedBaseAmount().signum());
    }

    private static TradingDecisionContext context() {
        TickerResp ticker = new TickerResp();
        ticker.setLast("50000");

        BalanceDetail base = new BalanceDetail();
        base.setCcy("BTC");
        base.setAvailBal("0");

        BalanceDetail quote = new BalanceDetail();
        quote.setCcy("USDT");
        quote.setAvailBal("100");

        InstrumentInfoResp instrument = new InstrumentInfoResp();
        instrument.setMinSz("0.00001");
        instrument.setLotSz("0.0001");
        instrument.setMaxMktAmt("1000");
        instrument.setMaxMktSz("10");

        return new TradingDecisionContext()
                .setTicker(ticker)
                .setBaseBalance(base)
                .setQuoteBalance(quote)
                .setInstrument(instrument);
    }
}
