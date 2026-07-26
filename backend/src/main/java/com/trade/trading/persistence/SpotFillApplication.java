package com.trade.trading.persistence;

import java.math.BigDecimal;

/** Result of applying one cumulative exchange fill snapshot to the position ledger. */
public record SpotFillApplication(
        boolean changed,
        boolean firstApplication,
        BigDecimal appliedQuantityDelta
) {
    public static SpotFillApplication unchanged() {
        return new SpotFillApplication(false, false, BigDecimal.ZERO);
    }
}
