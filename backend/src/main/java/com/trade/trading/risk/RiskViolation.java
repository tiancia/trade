package com.trade.trading.risk;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class RiskViolation {
    private String code;
    private String reason;

    public static RiskViolation of(String code, String reason) {
        return new RiskViolation()
                .setCode(code)
                .setReason(reason);
    }
}
