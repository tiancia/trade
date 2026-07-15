package com.trade.trading.order;

public record OrderTransitionResult(TradingOrder order, boolean changed) {
}
