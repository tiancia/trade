package com.trade.trading.domain.order;

import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrderIdentityTest {
    @Test
    void preservesVersionOneFingerprintAndClientOrderId() {
        var identity = new OrderIdentity();
        var decision = new StrategyDecision().setStrategyId("trend").setAction(TradingAction.BUY)
                .setBuyQuoteAmount(new BigDecimal("100.00"));
        var snapshot = new OrderIdentity.Snapshot(List.of(
                new OrderIdentity.CandleIdentity("1800000000000", false),
                new OrderIdentity.CandleIdentity("1700000000000", true)), List.of(), "ticker", "decision");
        String key = identity.create("BTC-USDT", decision, snapshot, "100");
        assertEquals("45dcd926c9d476289ae7723d2bec89dd28273275f877b4a4cbff589e639c1d35", key);
        assertEquals("stbu45dcd926c9d476289ae7723d2bec", identity.clientOrderId(key, "BUY"));
        assertNotEquals(key, identity.create("BTC-USDT", decision, snapshot, "101"));
    }
}
