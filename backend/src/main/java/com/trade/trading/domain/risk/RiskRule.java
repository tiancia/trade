package com.trade.trading.domain.risk;

import java.util.Optional;

public interface RiskRule {
    Optional<RiskViolation> evaluate(RiskContext context);
}
