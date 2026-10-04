package com.trade.trading.infrastructure.config;

import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.application.port.TradingStateStore;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.risk.ConsecutiveOpenActionsRule;
import com.trade.trading.domain.risk.DailyLossRule;
import com.trade.trading.domain.risk.LossCooldownRule;
import com.trade.trading.domain.risk.MaxDrawdownRule;
import com.trade.trading.domain.risk.OpenIntervalRule;
import com.trade.trading.domain.risk.RiskRule;
import com.trade.trading.domain.risk.RiskViolation;
import com.trade.trading.domain.risk.SingleOpenExposureRule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradingRiskConfigurationTest {
    private final TradingStateStore stateStore = mock(TradingStateStore.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RiskControlService.class)
            .withBean(TradingProperties.class, TradingProperties::new)
            .withBean(TradingStateStore.class, () -> stateStore)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void wiresNamedRulesInOriginalOrderAndIgnoresUnrelatedRuleBeans() {
        when(stateStore.getState()).thenReturn(new TradingState());

        runner.withUserConfiguration(TradingRiskConfiguration.class)
                .withBean("unrelatedRule", RiskRule.class,
                        () -> context -> Optional.of(RiskViolation.of("UNRELATED", "unrelated rule")))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RiskControlService.class);
                    List<?> rules = context.getBean("tradingRiskRules", List.class);
                    assertEquals(List.of(LossCooldownRule.class, MaxDrawdownRule.class, DailyLossRule.class,
                                    OpenIntervalRule.class, ConsecutiveOpenActionsRule.class, SingleOpenExposureRule.class),
                            rules.stream().map(Object::getClass).toList());

                    var assessment = context.getBean(RiskControlService.class).evaluate(
                            new StrategyDecision().setAction(TradingAction.HOLD), new TradingDecisionContext());
                    assertThat(assessment.isAllowed()).isTrue();
                });
    }

    @Test
    void evaluatesInjectedRulesAndPreservesPrimaryViolationAndMetrics() {
        when(stateStore.getState()).thenReturn(new TradingState());

        runner.withUserConfiguration(CustomRiskConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RiskControlService.class);
            var assessment = context.getBean(RiskControlService.class).evaluate(
                    new StrategyDecision().setAction(TradingAction.HOLD), new TradingDecisionContext());

            assertThat(assessment.isAllowed()).isFalse();
            assertThat(assessment.getViolations()).extracting(RiskViolation::getCode)
                    .containsExactly("CUSTOM_FIRST", "CUSTOM_SECOND");
            assertThat(assessment.skipReason()).isEqualTo("first injected rule");
            assertThat(context.getBean(MeterRegistry.class).get("trade.trading.risk.assessments")
                    .tags("outcome", "blocked", "primary_rule", "custom_first").counter().count()).isEqualTo(1.0);
        });
    }

    @Test
    void missingRuleConfigurationPreventsStartup() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void emptyRuleConfigurationPreventsStartup() {
        runner.withUserConfiguration(EmptyRiskConfiguration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("Risk rules must not be null or empty");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRiskConfiguration {
        @Bean("tradingRiskRules")
        List<RiskRule> tradingRiskRules() {
            return List.of(
                    context -> Optional.of(RiskViolation.of("CUSTOM_FIRST", "first injected rule")),
                    context -> Optional.of(RiskViolation.of("CUSTOM_SECOND", "second injected rule"))
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyRiskConfiguration {
        @Bean("tradingRiskRules")
        List<RiskRule> tradingRiskRules() {
            return List.of();
        }
    }
}
