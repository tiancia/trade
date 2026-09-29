package com.trade.trading.domain.order;

import com.trade.common.support.TradingMath;
import java.math.BigDecimal;
import java.util.Locale;

/** Identity, terminal consistency, and fee-aware cumulative settlement rules. */
public final class OrderSettlementPolicy {
    private final String baseCurrency;
    private final String quoteCurrency;
    public OrderSettlementPolicy(String baseCurrency, String quoteCurrency) {
        this.baseCurrency = baseCurrency; this.quoteCurrency = quoteCurrency;
    }
    public record Plan(OrderStatus target, String exchangeState, String exchangeOrderId,
                       String executionStatus, OrderFill fill, FillAmounts amounts) {}
    public Plan prepare(TradingOrder local, ExchangeOrderObservation observed) {
        verifyIdentity(local, observed);
        FillAmounts amounts = fillAmounts(observed);
        OrderFill fill = amounts.filledSize().signum() > 0
                ? new OrderFill(amounts.filledSize(), amounts.averagePrice(), amounts.fee(), observed.getFeeCcy()) : null;
        String state = normalizeState(observed.getState());
        OrderStatus target = switch (state) {
            case "filled" -> OrderStatus.FILLED;
            case "partially_filled" -> OrderStatus.PARTIALLY_FILLED;
            case "canceled", "mmp_canceled" -> OrderStatus.CANCELED;
            case "live" -> local.getStatus() == OrderStatus.CANCEL_PENDING ? null : OrderStatus.ACCEPTED;
            default -> throw new IllegalStateException("Unsupported OKX order state: " + observed.getState());
        };
        String executionStatus = "live".equals(state) ? "FILL_UNCONFIRMED" : target.name();
        return new Plan(target, state, firstText(observed.getOrdId(), local.getExchangeOrderId()),
                executionStatus, fill, amounts);
    }
    public CumulativeSpotAmounts cumulativeSpotAmounts(
            TradingOrder localOrder,
            ExchangeOrderObservation exchangeOrder,
            FillAmounts amounts
    ) {
        BigDecimal filled = amounts.filledSize();
        if ("buy".equalsIgnoreCase(localOrder.getSide())) {
            BigDecimal netBase = sameCurrency(exchangeOrder.getFeeCcy(), baseCurrency)
                    ? filled.add(amounts.fee())
                    : filled;
            BigDecimal quoteCost = filled.multiply(amounts.averagePrice());
            if (sameCurrency(exchangeOrder.getFeeCcy(), quoteCurrency)) {
                // OKX represents charged fees as negative values and rebates
                // as positive values.
                quoteCost = quoteCost.subtract(amounts.fee());
            }
            return new CumulativeSpotAmounts(netBase, quoteCost);
        }

        BigDecimal reduction = sameCurrency(exchangeOrder.getFeeCcy(), baseCurrency)
                ? filled.subtract(amounts.fee())
                : filled;
        return new CumulativeSpotAmounts(reduction, BigDecimal.ZERO);
    }

    private static FillAmounts fillAmounts(ExchangeOrderObservation order) {
        // This broker currently submits market/taker orders. OKX also exposes a
        // separate positive rebate field for maker scenarios; accepting one
        // without an explicit ledger column would silently distort cost, so
        // fail closed until that execution type is deliberately supported.
        if (TradingMath.decimal(order.getRebate()).signum() != 0) {
            throw new IllegalStateException(
                    "Separate OKX rebate accounting is not supported by the live settlement ledger"
            );
        }
        BigDecimal filledSize = TradingMath.decimal(order.getAccFillSz());
        if (filledSize.signum() <= 0) {
            filledSize = TradingMath.decimal(order.getFillSz());
        }
        BigDecimal averagePrice = TradingMath.decimal(order.getAvgPx());
        if (averagePrice.signum() <= 0) {
            averagePrice = TradingMath.decimal(order.getFillPx());
        }
        return new FillAmounts(
                filledSize,
                averagePrice,
                TradingMath.decimal(order.getFee())
        );
    }

    private static void verifyIdentity(TradingOrder localOrder, ExchangeOrderObservation exchangeOrder) {
        if (!hasText(exchangeOrder.getOrdId()) && !hasText(exchangeOrder.getClOrdId())) {
            throw new IllegalStateException("Exchange order snapshot has no deterministic identity");
        }
        if (hasText(exchangeOrder.getClOrdId())
                && !exchangeOrder.getClOrdId().equals(localOrder.getClientOrderId())) {
            throw new IllegalStateException("Exchange clOrdId does not match local order");
        }
        if (hasText(exchangeOrder.getOrdId())
                && hasText(localOrder.getExchangeOrderId())
                && !exchangeOrder.getOrdId().equals(localOrder.getExchangeOrderId())) {
            throw new IllegalStateException("Exchange ordId does not match local order");
        }
        if (hasText(exchangeOrder.getInstId())
                && !exchangeOrder.getInstId().equals(localOrder.getInstId())) {
            throw new IllegalStateException("Exchange instrument does not match local order");
        }
        if (hasText(exchangeOrder.getSide())
                && !exchangeOrder.getSide().equalsIgnoreCase(localOrder.getSide())) {
            throw new IllegalStateException("Exchange side does not match local order");
        }
        if (localOrder.getStatus().isTerminal()) {
            String exchangeState = normalizeState(exchangeOrder.getState());
            boolean agrees = (localOrder.getStatus() == OrderStatus.FILLED && "filled".equals(exchangeState))
                    || (localOrder.getStatus() == OrderStatus.CANCELED
                    && ("canceled".equals(exchangeState) || "mmp_canceled".equals(exchangeState)))
                    || localOrder.getStatus() == OrderStatus.REJECTED;
            if (!agrees) {
                throw new IllegalStateException(
                        "Terminal order disagrees with exchange: local="
                                + localOrder.getStatus() + ", exchange=" + exchangeState
                );
            }
        }
    }

    private static String normalizeState(String state) {
        return state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean sameCurrency(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstText(String first, String fallback) {
        return hasText(first) ? first : fallback;
    }
    public record FillAmounts(
            BigDecimal filledSize,
            BigDecimal averagePrice,
            BigDecimal fee
    ) {
    }

    public record CumulativeSpotAmounts(
            BigDecimal positionQuantity,
            BigDecimal quoteCost
    ) {
    }
}
