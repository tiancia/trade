package com.trade.polymarket.domain.rule;

import com.trade.polymarket.domain.model.PolymarketOutcomeSnapshot;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class PolymarketMarketFiltersTest {
    private final MarketEligibilityPolicy policy = new MarketEligibilityPolicy(true, 10, 1,
            new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("0.05"), new BigDecimal("10"));

    @Test
    void timeAndTurnoverLimitsAcceptExactBoundariesAndRejectMissingDates() {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        assertNull(PolymarketMarketFilters.marketTurnoverSkipReason(policy,
                now.plusSeconds(600).toString(), "100", "50", now));
        assertNull(PolymarketMarketFilters.marketTurnoverSkipReason(policy,
                now.plusSeconds(3600).toString(), "100", "50", now));
        assertEquals("market resolves beyond configured short-term window",
                PolymarketMarketFilters.marketTurnoverSkipReason(policy,
                        now.plusSeconds(3660).toString(), "100", "50", now));
        assertEquals("market end date is missing",
                PolymarketMarketFilters.marketTurnoverSkipReason(policy, null, "100", "50", now));
    }

    @Test
    void spreadLimitIsInclusiveAndCrossedBooksRemainRejected() {
        var outcome = new PolymarketOutcomeSnapshot().setBestBid(new BigDecimal("0.50"))
                .setBestAsk(new BigDecimal("0.55")).setTopAskLiquidityUsdc(new BigDecimal("10"));
        assertNull(PolymarketMarketFilters.outcomeLiquiditySkipReason(policy, outcome));
        outcome.setBestAsk(new BigDecimal("0.56"));
        assertEquals("outcome spread is wider than configured maximum",
                PolymarketMarketFilters.outcomeLiquiditySkipReason(policy, outcome));
        outcome.setBestAsk(new BigDecimal("0.49"));
        assertEquals("outcome order book is crossed",
                PolymarketMarketFilters.outcomeLiquiditySkipReason(policy, outcome));
    }
}
