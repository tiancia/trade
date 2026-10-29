package com.trade.trading.domain.order;

import java.math.BigDecimal;

/** Result of applying one cumulative exchange fill snapshot to the position ledger. */
public record SpotFillApplication(
        boolean changed,
        boolean firstApplication,
        BigDecimal appliedQuantityDelta,
        boolean openedPosition,
        boolean closedPosition,
        BigDecimal realizedPnlDelta
) {
    public SpotFillApplication(boolean changed, boolean firstApplication, BigDecimal appliedQuantityDelta) {
        this(changed, firstApplication, appliedQuantityDelta, false, false, BigDecimal.ZERO);
    }
    public static SpotFillApplication unchanged() {
        return new SpotFillApplication(false, false, BigDecimal.ZERO);
    }
}
