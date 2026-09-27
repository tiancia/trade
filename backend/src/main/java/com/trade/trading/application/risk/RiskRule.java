package com.trade.trading.application.risk;

import com.trade.trading.domain.risk.RiskViolation;

import java.util.Optional;

public interface RiskRule {
    Optional<RiskViolation> evaluate(RiskContext context);
}
