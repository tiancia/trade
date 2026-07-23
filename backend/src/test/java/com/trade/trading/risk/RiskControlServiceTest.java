package com.trade.trading.risk;

import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.persistence.TradingStateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskControlServiceTest {
    private static final Instant NOW = Instant.parse("2026-05-17T02:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void consecutiveLossesTriggerCooldownAndOnlyBlockOpenActions() {
        TradingProperties properties = properties();
        TradingStateRepository repository = repository();
        RiskControlService service = service(properties, repository, NOW);

        assertTrue(service.evaluate(openDecision(TradingAction.BUY), context("1000")).isAllowed());
        assertTrue(service.evaluate(openDecision(TradingAction.BUY), context("990")).isAllowed());
        assertTrue(service.evaluate(openDecision(TradingAction.BUY), context("980")).isAllowed());

        RiskAssessment thirdLoss = service.evaluate(openDecision(TradingAction.BUY), context("970"));

        assertFalse(thirdLoss.isAllowed());
        assertTrue(thirdLoss.skipReason().startsWith("RISK_COOLDOWN:"));
        TradingRiskState riskState = repository.getState().getRiskState();
        assertEquals(3, riskState.getConsecutiveLosses());
        assertEquals(NOW.plusMillis(properties.getRisk().getLossCooldownMs()).toString(),
                riskState.getLossCooldownUntil());

        RiskAssessment buyDuringCooldown = service.evaluate(openDecision(TradingAction.BUY), context("970"));
        RiskAssessment sellDuringCooldown = service.evaluate(closeDecision(TradingAction.SELL), context("970"));

        assertFalse(buyDuringCooldown.isAllowed());
        assertTrue(sellDuringCooldown.isAllowed());
    }

    @Test
    void maxDrawdownBlocksOpenActionsAtConfiguredLimit() {
        TradingProperties properties = properties();
        properties.getRisk().setMaxDailyLossRatio(BigDecimal.ONE);
        TradingStateRepository repository = repository();
        repository.recordRiskState(new TradingRiskState()
                .setCurrentEquity(new BigDecimal("1000"))
                .setEquityHighWatermark(new BigDecimal("1000"))
                .setDayStartEquity(new BigDecimal("1000"))
                .setDayStartDate("2026-05-17"));

        RiskAssessment assessment = service(properties, repository, NOW)
                .evaluate(openDecision(TradingAction.BUY), context("800"));

        assertFalse(assessment.isAllowed());
        assertTrue(assessment.skipReason().startsWith("RISK_MAX_DRAWDOWN:"));
    }

    @Test
    void dailyLossBlocksOpenActionsAndResetsOnNextConfiguredDay() {
        TradingProperties properties = properties();
        properties.getRisk().setMaxDrawdownRatio(BigDecimal.ONE);
        TradingStateRepository repository = repository();
        repository.recordRiskState(new TradingRiskState()
                .setCurrentEquity(new BigDecimal("1000"))
                .setEquityHighWatermark(new BigDecimal("1000"))
                .setDayStartEquity(new BigDecimal("1000"))
                .setDayStartDate("2026-05-17"));

        RiskAssessment sameDay = service(properties, repository, NOW)
                .evaluate(openDecision(TradingAction.BUY), context("950"));

        assertFalse(sameDay.isAllowed());
        assertTrue(sameDay.skipReason().startsWith("RISK_DAILY_LOSS:"));

        RiskAssessment nextDay = service(properties, repository, Instant.parse("2026-05-17T16:01:00Z"))
                .evaluate(openDecision(TradingAction.BUY), context("950"));

        assertTrue(nextDay.isAllowed());
        assertEquals("2026-05-18", repository.getState().getRiskState().getDayStartDate());
        assertDecimal("950", repository.getState().getRiskState().getDayStartEquity());
    }

    @Test
    void conservativeOpenDefaultsBlockFastAndRepeatedOpenActions() {
        TradingProperties properties = properties();
        TradingStateRepository repository = repository();
        repository.recordRiskState(new TradingRiskState()
                .setCurrentEquity(new BigDecimal("1000"))
                .setEquityHighWatermark(new BigDecimal("1000"))
                .setDayStartEquity(new BigDecimal("1000"))
                .setDayStartDate("2026-05-17")
                .setLastTradeTime(NOW.minusMillis(60_000).toString()));

        RiskAssessment fastOpen = service(properties, repository, NOW)
                .evaluate(openDecision(TradingAction.BUY), context("1000"));

        assertFalse(fastOpen.isAllowed());
        assertTrue(fastOpen.skipReason().startsWith("RISK_MIN_OPEN_INTERVAL:"));

        repository.recordRiskState(repository.getState().getRiskState()
                .setLastTradeTime(NOW.minusMillis(properties.getRisk().getMinOpenIntervalMs() + 1).toString())
                .setConsecutiveOpenActions(2));

        RiskAssessment repeatedOpen = service(properties, repository, NOW)
                .evaluate(openDecision(TradingAction.BUY), context("1000"));

        assertFalse(repeatedOpen.isAllowed());
        assertTrue(repeatedOpen.skipReason().startsWith("RISK_CONSECUTIVE_OPEN_ACTIONS:"));
    }

    @Test
    void derivativeSingleOpenExposureBlocksOversizedOpenAction() {
        TradingProperties properties = properties();
        properties.setInstType("SWAP");
        properties.getRisk().setMinOpenIntervalMs(0);
        TradingStateRepository repository = repository();

        RiskAssessment assessment = service(properties, repository, NOW)
                .evaluate(openDecision(TradingAction.OPEN_LONG).setOrderSize(new BigDecimal("3")), context("1000", "50"));

        assertFalse(assessment.isAllowed());
        assertTrue(assessment.skipReason().startsWith("RISK_SINGLE_OPEN_EXPOSURE:"));
    }

    @Test
    void recordsRiskAssessmentAndViolationMetrics() {
        TradingProperties properties = properties();
        properties.getRisk().setMaxDailyLossRatio(BigDecimal.ONE);
        TradingStateRepository repository = repository();
        repository.recordRiskState(new TradingRiskState()
                .setCurrentEquity(new BigDecimal("1000"))
                .setEquityHighWatermark(new BigDecimal("1000"))
                .setDayStartEquity(new BigDecimal("1000"))
                .setDayStartDate("2026-05-17"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RiskControlService service = new RiskControlService(
                properties,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                null,
                meterRegistry
        );

        RiskAssessment assessment = service.evaluate(openDecision(TradingAction.BUY), context("800"));

        assertFalse(assessment.isAllowed());
        assertEquals(1.0, meterRegistry.get("trade.trading.risk.assessments")
                .tags("outcome", "blocked", "primary_rule", "risk_max_drawdown")
                .counter()
                .count());
        assertEquals(1.0, meterRegistry.get("trade.trading.risk.violations")
                .tag("rule", "risk_max_drawdown")
                .counter()
                .count());
    }

    private RiskControlService service(TradingProperties properties, TradingStateRepository repository, Instant now) {
        return new RiskControlService(properties, repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    private TradingStateRepository repository() {
        return new TradingStateRepository(tempDir.resolve("trading-state-" + System.nanoTime() + ".json"));
    }

    private static TradingProperties properties() {
        TradingProperties properties = new TradingProperties();
        properties.getRisk().setMinOpenIntervalMs(600_000);
        return properties;
    }

    private static StrategyDecision openDecision(TradingAction action) {
        return decision(action)
                .setBuyQuoteAmount(new BigDecimal("10"))
                .setOrderSize(BigDecimal.ONE);
    }

    private static StrategyDecision closeDecision(TradingAction action) {
        return decision(action)
                .setSellBaseAmount(new BigDecimal("0.01"))
                .setOrderSize(BigDecimal.ONE);
    }

    private static StrategyDecision decision(TradingAction action) {
        return new StrategyDecision()
                .setAction(action)
                .setReason("test");
    }

    private static TradingDecisionContext context(String equity) {
        return context(equity, "50000");
    }

    private static TradingDecisionContext context(String equity, String lastPrice) {
        AccountBalanceResp accountBalance = new AccountBalanceResp();
        accountBalance.setTotalEq(equity);

        TickerResp ticker = new TickerResp();
        ticker.setLast(lastPrice);

        return new TradingDecisionContext()
                .setAccountBalance(accountBalance)
                .setTicker(ticker);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
