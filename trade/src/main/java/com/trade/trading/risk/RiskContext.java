package com.trade.trading.risk;

import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.support.TradingMath;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class RiskContext {
    private AiTradingDecision decision;
    private TradingDecisionContext decisionContext;
    private TradingProperties properties;
    private TradingRiskState riskState;
    private Instant now;
    private BigDecimal currentEquity = BigDecimal.ZERO;

    public TradingAction action() {
        return decision == null ? null : decision.getAction();
    }

    public boolean isOpenAction() {
        TradingAction action = action();
        return action != null && action.isOpenAction();
    }

    public boolean isExecutableOpenAction() {
        TradingAction action = action();
        if (action == null || !action.isOpenAction() || properties == null) {
            return false;
        }
        return switch (action) {
            case BUY -> properties.isSpotInstrument();
            case OPEN_LONG -> properties.isDerivativeInstrument();
            case OPEN_SHORT -> properties.isDerivativeInstrument() && properties.isShortEnabled();
            default -> false;
        };
    }

    public boolean isCloseAction() {
        TradingAction action = action();
        return action != null && action.isCloseAction();
    }

    public BigDecimal lastPrice() {
        if (decisionContext == null || decisionContext.getTicker() == null) {
            return BigDecimal.ZERO;
        }
        return TradingMath.decimal(decisionContext.getTicker().getLast());
    }

    public BigDecimal requestedOpenExposure() {
        if (decision == null || action() == null) {
            return BigDecimal.ZERO;
        }
        return switch (action()) {
            case BUY -> zeroIfNull(decision.getBuyQuoteAmountUsdt());
            case OPEN_LONG, OPEN_SHORT -> zeroIfNull(decision.getOrderSize()).multiply(lastPrice());
            default -> BigDecimal.ZERO;
        };
    }

    public static BigDecimal estimateEquity(TradingDecisionContext context) {
        if (context == null) {
            return BigDecimal.ZERO;
        }

        AccountBalanceResp accountBalance = context.getAccountBalance();
        BigDecimal totalEq = TradingMath.decimal(accountBalance == null ? null : accountBalance.getTotalEq());
        if (totalEq.signum() > 0) {
            return totalEq;
        }

        BigDecimal quote = balanceAmount(context.getQuoteBalance());
        BigDecimal base = balanceAmount(context.getBaseBalance());
        BigDecimal lastPrice = context.getTicker() == null
                ? BigDecimal.ZERO
                : TradingMath.decimal(context.getTicker().getLast());
        return quote.add(base.multiply(lastPrice));
    }

    private static BigDecimal balanceAmount(BalanceDetail detail) {
        if (detail == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal availBal = TradingMath.decimal(detail.getAvailBal());
        if (availBal.signum() > 0) {
            return availBal;
        }
        BigDecimal cashBal = TradingMath.decimal(detail.getCashBal());
        if (cashBal.signum() > 0) {
            return cashBal;
        }
        return TradingMath.decimal(detail.getEq());
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
