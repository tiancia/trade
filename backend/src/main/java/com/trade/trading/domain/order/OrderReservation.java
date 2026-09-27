package com.trade.trading.domain.order;

public record OrderReservation(TradingOrder order, boolean acquired) {
}
