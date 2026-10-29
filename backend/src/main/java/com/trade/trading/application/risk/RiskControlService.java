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
import com.trade.trading.port.TradingStateStore;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.domain.model.ExecutionMode;
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
import java.util.function.UnaryOperator;

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
    private final PaperAccountService paperAccounts;

    @Autowired
    public RiskControlService(
            TradingProperties properties,
            TradingStateStore stateRepository,
            @Qualifier("tradingRiskRules") List<RiskRule> rules,
            MeterRegistry meterRegistry,
            PaperAccountService paperAccounts
    ) {
        this(properties, stateRepository, Clock.systemUTC(), rules, meterRegistry, paperAccounts);
    }

    public RiskControlService(
            TradingProperties properties,
            TradingStateStore stateRepository,
            Clock clock,
            List<RiskRule> rules
    ) {
        this(properties, stateRepository, clock, rules, null, null);
    }

    RiskControlService(
            TradingProperties properties,
            TradingStateStore stateRepository,
            Clock clock,
            List<RiskRule> rules,
            MeterRegistry meterRegistry
    ) {
        this(properties, stateRepository, clock, rules, meterRegistry, null);
    }

    public RiskControlService(TradingProperties properties, TradingStateStore stateRepository,
                              Clock clock, List<RiskRule> rules, PaperAccountService paperAccounts) {
        this(properties, stateRepository, clock, rules, null, paperAccounts);
    }

    private RiskControlService(
            TradingProperties properties,
            TradingStateStore stateRepository,
            Clock clock,
            List<RiskRule> rules,
            MeterRegistry meterRegistry,
            PaperAccountService paperAccounts
    ) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("Risk rules must not be null or empty");
        }
        this.properties = properties;
        this.stateRepository = stateRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.rules = List.copyOf(rules);
        this.meterRegistry = meterRegistry;
        this.paperAccounts = paperAccounts;
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
        if (riskProperties.isEnabled()) {
            for (RiskRule rule : rules) {
                rule.evaluate(context).ifPresent(violations::add);
            }
        }
        if (violations.isEmpty()) {
            recordAssessment("allowed", "none", violations);
            return RiskAssessment.allowed(riskState, currentEquity);
        }

        String reason = violations.getFirst().getReason();
        riskState = updateRiskState(decisionContext,
                current -> RiskStateTransitions.copyRiskState(current).setLastRiskReason(reason));
        recordAssessment("blocked", metricValue(violations.getFirst().getCode()), violations);
        return RiskAssessment.blocked(riskState, currentEquity, violations);
    }

    public void recordExecutedAction(StrategyDecision decision, TradingDecisionContext decisionContext) {
        if (decision == null || decision.getAction() == null || !riskProperties().isEnabled()) {
            return;
        }

        BigDecimal equity = TradingMarketInputs.estimatedEquity(decisionContext);
        Instant now = Instant.now(clock);
        updateRiskState(decisionContext, current -> transitions().executed(current, decision.getAction(), equity, now));
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
        stateRepository.updateRiskState(current -> transitions().executed(current, action, BigDecimal.ZERO,
                executedAt == null ? Instant.now(clock) : executedAt));
    }

    public void recordReconciledFill(TradingAction action, Instant executedAt,
                                     com.trade.trading.domain.order.SpotFillApplication fill) {
        if (!fill.changed()) return;
        stateRepository.updateRiskState(current -> transitions().filled(current, action, BigDecimal.ZERO,
                fill.openedPosition(), fill.closedPosition(), fill.realizedPnlDelta(),
                executedAt == null ? Instant.now(clock) : executedAt, fill.firstApplication()));
    }

    private TradingRiskState refreshRiskState(TradingDecisionContext decisionContext) {
        BigDecimal equity = TradingMarketInputs.estimatedEquity(decisionContext);
        Instant now = Instant.now(clock);
        return updateRiskState(decisionContext, current -> transitions().refresh(current, equity, now));
    }

    private TradingRiskState updateRiskState(TradingDecisionContext context, UnaryOperator<TradingRiskState> transition) {
        return paperAccounts != null && properties.getExecutionMode() == ExecutionMode.PAPER
                ? paperAccounts.updateRiskState(context, transition)
                : stateRepository.updateRiskState(transition);
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
                    .tag("rule", metricValue(violation.getCode()))
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
