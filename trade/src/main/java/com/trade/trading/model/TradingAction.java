package com.trade.trading.model;

public enum TradingAction {
    BUY,
    HOLD,
    SELL,
    OPEN_LONG,
    CLOSE_LONG,
    OPEN_SHORT,
    CLOSE_SHORT;

    public boolean isOpenAction() {
        return this == BUY || this == OPEN_LONG || this == OPEN_SHORT;
    }

    public boolean isCloseAction() {
        return this == SELL || this == CLOSE_LONG || this == CLOSE_SHORT;
    }

    public boolean isDerivativeAction() {
        return this == OPEN_LONG
                || this == CLOSE_LONG
                || this == OPEN_SHORT
                || this == CLOSE_SHORT;
    }
}
