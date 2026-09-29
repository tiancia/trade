package com.trade.trading.domain.order;

import com.trade.trading.domain.model.TradingAction;

/** Instrument/action compatibility; live safety switches are enforced by the use case. */
public final class ExecutionEligibility {
    private ExecutionEligibility() {}
    public static String skipReason(TradingAction action, boolean spot, boolean shortsAllowed) {
        if (action == TradingAction.BUY && !spot) { return "BUY skipped: use OPEN_LONG for non-spot instruments"; }
        if (action == TradingAction.SELL && !spot) { return "SELL skipped: use CLOSE_LONG or OPEN_SHORT for non-spot instruments"; }
        if (action != null && action.isDerivativeAction()) {
            if (spot) { return "Derivative action skipped: current instrument type is SPOT"; }
            if ((action == TradingAction.OPEN_SHORT || action == TradingAction.CLOSE_SHORT) && !shortsAllowed) {
                return action + " skipped: short trading is disabled by strategy.allowShort";
            }
        }
        return null;
    }
}
