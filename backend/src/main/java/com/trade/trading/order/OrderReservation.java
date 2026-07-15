package com.trade.trading.order;

public record OrderReservation(TradingOrder order, boolean acquired) {
}
