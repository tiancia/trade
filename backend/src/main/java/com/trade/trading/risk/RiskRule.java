package com.trade.trading.risk;

import java.util.Optional;

public interface RiskRule {
    Optional<RiskViolation> evaluate(RiskContext context);
}
