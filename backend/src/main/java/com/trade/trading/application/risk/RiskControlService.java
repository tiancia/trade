package com.trade.trading.application.risk;

import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.application.market.TradingMarketInputs;
import com.trade.trading.domain.model.TradingRiskState;
import com.trade.trading.domain.risk.RiskAssessment;
import com.trade.trading.domain.risk.RiskContext;
import com.trade.trading.domain.risk.RiskRule;
import com.trade.trading.domain.risk.RiskStateTransitions;
import com.trade.trading.domain.risk.RiskViolation;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.application.port.TradingStateStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coordinates risk-state persistence, domain evaluation, and assessment metrics.
 *
 * <p>The first violation becomes the short skip reason while all violations
 * remain available on the assessment.</p>
 */
@Component
public class RiskControlService {
    private final TradingProperties properties;
    private final TradingStateStore stateRepository;
    private final Clock clock;
    private final List<RiskRule> rules;
    private final MeterRegistry meterRegistry;

    @Autowired
    public RiskControlService(
            TradingProperties properties,
            TradingStateStore stateRepository,
            @Qualifier("tradingRiskRules") List<RiskRule> rules,
            MeterRegistry meterRegistry
    ) {
        this(properties, stateRepository, Clock.systemUTC(), rules, meterRegistry);
    }

    public RiskControlService(
            TradingProperties properties,
            TradingStateStore stateRepository,
            Clock clock,
            List<RiskRule> rules
    ) {
        this(properties, stateRepository, clock, rules, null);
    }

    RiskControlService(
            TradingProperties properties,
            TradingStateStore stateRepository,
            Clock clock,
            List<RiskRule> rules,
            MeterRegistry meterRegistry
    ) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("Risk rules must not be null or empty");
        }
        this.properties = properties;
        this.stateRepository = stateRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.rules = List.copyOf(rules);
        this.meterRegistry = meterRegistry;
    }

    public RiskAssessment evaluate(StrategyDecision decision, TradingDecisionContext decisionContext) {
        TradingProperties.RiskProperties riskProperties = riskProperties();
        // Refresh risk state first so daily loss, drawdown, and cooldown rules
        // compare the current decision against the latest estimated equity.
        TradingRiskState riskState = refreshRiskState(decisionContext);
        BigDecimal currentEquity = zeroIfNull(riskState.getCurrentEquity());

        Instant now = Instant.now(clock);
        RiskContext context = new RiskContext()
                .setDecision(decision)
                .setLastPrice(TradingMarketInputs.lastPrice(decisionContext))
                .setPolicy(RiskInputs.policy(riskProperties))
                .setSpotInstrument(properties.isSpotInstrument())
                .setDerivativeInstrument(properties.isDerivativeInstrument())
                .setShortEnabled(properties.isShortEnabled())
                .setRiskState(riskState)
                .setNow(now)
                .setCurrentEquity(currentEquity);

        List<RiskViolation> violations = new ArrayList<>();
        for (RiskRule rule : rules) {
            if (!riskProperties.isEnabled()) {
                continue;
            }
            rule.evaluate(context).ifPresent(violations::add);
        }
        if (violations.isEmpty()) {
            recordAssessment("allowed", "none", violations);
            return RiskAssessment.allowed(riskState, currentEquity);
        }

        riskState.setLastRiskReason(violations.getFirst().getReason());
        stateRepository.recordRiskState(riskState);
        recordAssessment("blocked", metricValue(violations.getFirst().getCode()), violations);
        return RiskAssessment.blocked(riskState, currentEquity, violations);
    }

    public void recordExecutedAction(StrategyDecision decision, TradingDecisionContext decisionContext) {
        if (decision == null || decision.getAction() == null || !riskProperties().isEnabled()) {
            return;
        }

        TradingRiskState riskState = transitions().executed(
                stateRepository.getState().getRiskState(), decision.getAction(),
                TradingMarketInputs.estimatedEquity(decisionContext), Instant.now(clock));
        stateRepository.recordRiskState(riskState);
    }

    /**
     * Records the first durably applied fill for a live order.
     *
     * <p>Unlike order acceptance, this method represents an actual capital
     * change. The fill ledger calls it only on the first cumulative application
     * so partial-fill refreshes do not inflate consecutive-action counters.</p>
     */
    public void recordReconciledAction(TradingAction action, Instant executedAt) {
        if (action == null || !riskProperties().isEnabled()) {
            return;
        }
        TradingRiskState riskState = transitions().executed(
                stateRepository.getState().getRiskState(), action, BigDecimal.ZERO,
                executedAt == null ? Instant.now(clock) : executedAt);
        stateRepository.recordRiskState(riskState);
    }

    private TradingRiskState refreshRiskState(TradingDecisionContext decisionContext) {
        TradingRiskState source = stateRepository.getState().getRiskState();
        BigDecimal equity = TradingMarketInputs.estimatedEquity(decisionContext);
        TradingRiskState state = transitions().refresh(source, equity, Instant.now(clock));
        if (equity.signum() > 0) {
            stateRepository.recordRiskState(state);
        }
        return state;
    }

    private RiskStateTransitions transitions() {
        return new RiskStateTransitions(RiskInputs.policy(riskProperties()));
    }

    private TradingProperties.RiskProperties riskProperties() {
        if (properties.getRisk() == null) {
            properties.setRisk(new TradingProperties.RiskProperties());
        }
        return properties.getRisk();
    }

    private void recordAssessment(String outcome, String primaryRule, List<RiskViolation> violations) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("trade.trading.risk.assessments")
                .description("Trading risk assessment outcomes")
                .tag("outcome", outcome)
                .tag("primary_rule", primaryRule)
                .register(meterRegistry)
                .increment();
        for (RiskViolation violation : violations) {
            Counter.builder("trade.trading.risk.violations")
                    .description("Trading risk rule violations")
                    .tag("rule", metricValue(violation == null ? null : violation.getCode()))
                    .register(meterRegistry)
                    .increment();
        }
    }

    private static String metricValue(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_");
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
