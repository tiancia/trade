package com.trade.trading.domain.order;

import java.math.BigDecimal;

public record OrderFill(
        BigDecimal filledBaseAmount,
        BigDecimal averageFillPrice,
        BigDecimal fee,
        String feeCcy
) {
}
