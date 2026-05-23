package com.trade.trading.risk;

import com.trade.trading.model.TradingRiskState;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

@Data
@Accessors(chain = true)
public class RiskAssessment {
    private boolean allowed;
    private BigDecimal currentEquity;
    private TradingRiskState riskState;
    private List<RiskViolation> violations = List.of();

    public static RiskAssessment allowed(TradingRiskState riskState, BigDecimal currentEquity) {
        return new RiskAssessment()
                .setAllowed(true)
                .setRiskState(riskState)
                .setCurrentEquity(currentEquity)
                .setViolations(List.of());
    }

    public static RiskAssessment blocked(
            TradingRiskState riskState,
            BigDecimal currentEquity,
            List<RiskViolation> violations
    ) {
        return new RiskAssessment()
                .setAllowed(false)
                .setRiskState(riskState)
                .setCurrentEquity(currentEquity)
                .setViolations(violations == null ? List.of() : List.copyOf(violations));
    }

    public String skipReason() {
        if (violations == null || violations.isEmpty()) {
            return null;
        }
        return violations.getFirst().getReason();
    }
}
