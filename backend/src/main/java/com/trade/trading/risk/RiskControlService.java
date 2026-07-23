package com.trade.trading.risk;

import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.model.TradingState;
import com.trade.trading.persistence.TradingStateRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates application-side risk rules before a strategy decision can place orders.
 *
 * <p>The first violation becomes the short skip reason while all violations
 * remain available on the assessment.</p>
 */
@Component
public class RiskControlService {
    private final TradingProperties properties;
    private final TradingStateRepository stateRepository;
    private final Clock clock;
    private final List<RiskRule> rules;
    private final MeterRegistry meterRegistry;

    @Autowired
    public RiskControlService(
            TradingProperties properties,
            TradingStateRepository stateRepository,
            MeterRegistry meterRegistry
    ) {
        this(properties, stateRepository, Clock.systemUTC(), defaultRules(), meterRegistry);
    }

    public RiskControlService(TradingProperties properties, TradingStateRepository stateRepository) {
        this(properties, stateRepository, Clock.systemUTC(), defaultRules(), null);
    }

    public RiskControlService(TradingProperties properties, TradingStateRepository stateRepository, Clock clock) {
        this(properties, stateRepository, clock, defaultRules(), null);
    }

    public RiskControlService(
            TradingProperties properties,
            TradingStateRepository stateRepository,
            Clock clock,
            List<RiskRule> rules
    ) {
        this(properties, stateRepository, clock, rules, null);
    }

    RiskControlService(
            TradingProperties properties,
            TradingStateRepository stateRepository,
            Clock clock,
            List<RiskRule> rules,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.stateRepository = stateRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.rules = rules == null ? defaultRules() : List.copyOf(rules);
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
                .setDecisionContext(decisionContext)
                .setProperties(properties)
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

        TradingState tradingState = stateRepository.getState();
        TradingRiskState riskState = copyRiskState(tradingState.getRiskState());
        BigDecimal currentEquity = RiskContext.estimateEquity(decisionContext);
        if (currentEquity.signum() > 0) {
            riskState.setCurrentEquity(currentEquity);
            if (zeroIfNull(riskState.getEquityHighWatermark()).compareTo(currentEquity) < 0) {
                riskState.setEquityHighWatermark(currentEquity);
            }
        }

        riskState.setLastTradeTime(Instant.now(clock).toString());
        if (decision.getAction().isOpenAction()) {
            riskState.setConsecutiveOpenActions(riskState.getConsecutiveOpenActions() + 1);
        } else if (decision.getAction().isCloseAction()) {
            riskState.setConsecutiveOpenActions(0);
        }
        stateRepository.recordRiskState(riskState);
    }

    private TradingRiskState refreshRiskState(TradingDecisionContext decisionContext) {
        TradingState tradingState = stateRepository.getState();
        TradingRiskState riskState = copyRiskState(tradingState.getRiskState());
        BigDecimal currentEquity = RiskContext.estimateEquity(decisionContext);
        if (currentEquity.signum() <= 0) {
            return riskState;
        }

        Instant now = Instant.now(clock);
        applyDailyBoundary(riskState, currentEquity, now);
        applyEquityLossState(riskState, currentEquity, now);
        if (zeroIfNull(riskState.getEquityHighWatermark()).compareTo(currentEquity) < 0) {
            riskState.setEquityHighWatermark(currentEquity);
        }
        riskState.setCurrentEquity(currentEquity);
        stateRepository.recordRiskState(riskState);
        return riskState;
    }

    private void applyDailyBoundary(TradingRiskState riskState, BigDecimal currentEquity, Instant now) {
        String today = LocalDate.ofInstant(now, dailyZone()).toString();
        // A new risk day resets the reference equity used by daily loss checks.
        if (riskState.getDayStartDate() == null
                || !riskState.getDayStartDate().equals(today)
                || zeroIfNull(riskState.getDayStartEquity()).signum() <= 0) {
            riskState.setDayStartDate(today)
                    .setDayStartEquity(currentEquity);
        }
    }

    private void applyEquityLossState(TradingRiskState riskState, BigDecimal currentEquity, Instant now) {
        TradingProperties.RiskProperties riskProperties = riskProperties();
        BigDecimal previousEquity = zeroIfNull(riskState.getCurrentEquity());
        if (previousEquity.signum() <= 0) {
            riskState.setConsecutiveLosses(Math.max(0, riskState.getConsecutiveLosses()));
            return;
        }

        BigDecimal noise = previousEquity.multiply(zeroIfNull(riskProperties.getEquityNoiseRatio()));
        BigDecimal decline = previousEquity.subtract(currentEquity);
        boolean hasLoss = decline.compareTo(noise) > 0;
        Instant cooldownUntil = parseInstant(riskState.getLossCooldownUntil());
        boolean activeCooldown = cooldownUntil != null && now.isBefore(cooldownUntil);
        // Tiny equity movements below the noise ratio do not count as losses;
        // this prevents fees or mark-price jitter from triggering cooldowns.
        if (hasLoss) {
            riskState.setConsecutiveLosses(riskState.getConsecutiveLosses() + 1);
        } else if (!activeCooldown) {
            riskState.setConsecutiveLosses(0);
        }

        if (hasLoss
                && riskProperties.getMaxConsecutiveLosses() > 0
                && riskState.getConsecutiveLosses() >= riskProperties.getMaxConsecutiveLosses()
                && !activeCooldown) {
            riskState.setLossCooldownUntil(now.plusMillis(riskProperties.getLossCooldownMs()).toString());
        } else if (!activeCooldown) {
            riskState.setLossCooldownUntil(null);
        }
    }

    private ZoneId dailyZone() {
        String zone = riskProperties().getDailyZone();
        if (zone == null || zone.isBlank()) {
            return ZoneId.of("Asia/Shanghai");
        }
        return ZoneId.of(zone);
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

    private static List<RiskRule> defaultRules() {
        return List.of(
                new LossCooldownRule(),
                new MaxDrawdownRule(),
                new DailyLossRule(),
                new OpenIntervalRule(),
                new ConsecutiveOpenActionsRule(),
                new SingleOpenExposureRule()
        );
    }

    private static TradingRiskState copyRiskState(TradingRiskState source) {
        if (source == null) {
            return new TradingRiskState();
        }
        return new TradingRiskState()
                .setCurrentEquity(zeroIfNull(source.getCurrentEquity()))
                .setEquityHighWatermark(zeroIfNull(source.getEquityHighWatermark()))
                .setDayStartEquity(zeroIfNull(source.getDayStartEquity()))
                .setDayStartDate(source.getDayStartDate())
                .setConsecutiveLosses(source.getConsecutiveLosses())
                .setLossCooldownUntil(source.getLossCooldownUntil())
                .setLastTradeTime(source.getLastTradeTime())
                .setConsecutiveOpenActions(source.getConsecutiveOpenActions())
                .setLastRiskReason(source.getLastRiskReason());
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
