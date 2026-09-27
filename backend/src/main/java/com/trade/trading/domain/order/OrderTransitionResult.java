package com.trade.trading.domain.order;

public record OrderTransitionResult(TradingOrder order, boolean changed) {
}
