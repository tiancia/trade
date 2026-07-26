package com.trade.trading.order;

import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.common.support.TradingMath;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.TradingAction;
import com.trade.trading.persistence.SpotFillApplication;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.risk.RiskControlService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * Applies an exchange order observation as one database transaction.
 *
 * <p>The order transition, cumulative fill checkpoint, position/cost update,
 * and executed-action risk bookkeeping either commit together or roll back
 * together. This closes the former crash window between marking an order
 * terminal and updating the managed position.</p>
 */
@Component
public class OrderSettlementService {
    private final OrderLifecycleService lifecycleService;
    private final TradingStateRepository stateRepository;
    private final RiskControlService riskControlService;
    private final TradingProperties properties;

    public OrderSettlementService(
            OrderLifecycleService lifecycleService,
            TradingStateRepository stateRepository,
            RiskControlService riskControlService,
            TradingProperties properties
    ) {
        this.lifecycleService = lifecycleService;
        this.stateRepository = stateRepository;
        this.riskControlService = riskControlService;
        this.properties = properties;
    }

    @Transactional
    public OrderSettlementResult applyExchangeSnapshot(TradingOrder localOrder, OrderInfoResp exchangeOrder) {
        if (localOrder == null || exchangeOrder == null) {
            throw new IllegalArgumentException("Local and exchange order are required");
        }
        verifyIdentity(localOrder, exchangeOrder);

        FillAmounts amounts = fillAmounts(exchangeOrder);
        OrderFill fill = amounts.filledSize().signum() > 0
                ? new OrderFill(
                        amounts.filledSize(),
                        amounts.averagePrice(),
                        amounts.fee(),
                        exchangeOrder.getFeeCcy()
                )
                : null;
        String exchangeOrderId = firstText(exchangeOrder.getOrdId(), localOrder.getExchangeOrderId());
        String exchangeState = normalizeState(exchangeOrder.getState());

        OrderTransitionResult transition = switch (exchangeState) {
            case "filled" -> lifecycleService.markFilled(
                    localOrder.getIdempotencyKey(),
                    exchangeOrderId,
                    fill
            );
            case "partially_filled" -> lifecycleService.markPartiallyFilled(
                    localOrder.getIdempotencyKey(),
                    exchangeOrderId,
                    fill
            );
            case "canceled", "mmp_canceled" -> lifecycleService.markCanceled(
                    localOrder.getIdempotencyKey(),
                    exchangeOrderId,
                    fill
            );
            case "live" -> localOrder.getStatus() == OrderStatus.CANCEL_PENDING
                    ? new OrderTransitionResult(localOrder, false)
                    : lifecycleService.markAccepted(localOrder.getIdempotencyKey(), exchangeOrderId);
            default -> throw new IllegalStateException(
                    "Unsupported OKX order state: " + exchangeOrder.getState()
            );
        };

        SpotFillApplication application = SpotFillApplication.unchanged();
        if (properties.isSpotInstrument() && amounts.filledSize().signum() > 0) {
            CumulativeSpotAmounts spotAmounts = cumulativeSpotAmounts(localOrder, exchangeOrder, amounts);
            application = stateRepository.applyCumulativeSpotFill(
                    transition.order().getId(),
                    localOrder.getSide(),
                    amounts.filledSize(),
                    spotAmounts.positionQuantity(),
                    spotAmounts.quoteCost(),
                    amounts.averagePrice(),
                    amounts.fee(),
                    exchangeOrder.getFeeCcy(),
                    exchangeState,
                    exchangeUpdatedAt(exchangeOrder)
            );
            if (application.firstApplication()) {
                riskControlService.recordReconciledAction(
                        TradingAction.valueOf(localOrder.getAction()),
                        exchangeUpdatedAt(exchangeOrder)
                );
            }
        }

        String executionStatus = switch (exchangeState) {
            case "filled" -> OrderStatus.FILLED.name();
            case "partially_filled" -> OrderStatus.PARTIALLY_FILLED.name();
            case "canceled", "mmp_canceled" -> OrderStatus.CANCELED.name();
            case "live" -> "FILL_UNCONFIRMED";
            default -> throw new IllegalStateException("Unsupported OKX order state: " + exchangeState);
        };
        return new OrderSettlementResult(transition.order(), executionStatus, application);
    }

    private CumulativeSpotAmounts cumulativeSpotAmounts(
            TradingOrder localOrder,
            OrderInfoResp exchangeOrder,
            FillAmounts amounts
    ) {
        BigDecimal filled = amounts.filledSize();
        if ("buy".equalsIgnoreCase(localOrder.getSide())) {
            BigDecimal netBase = sameCurrency(exchangeOrder.getFeeCcy(), properties.getBaseCcy())
                    ? filled.add(amounts.fee())
                    : filled;
            BigDecimal quoteCost = filled.multiply(amounts.averagePrice());
            if (sameCurrency(exchangeOrder.getFeeCcy(), properties.getQuoteCcy())) {
                // OKX represents charged fees as negative values and rebates
                // as positive values.
                quoteCost = quoteCost.subtract(amounts.fee());
            }
            return new CumulativeSpotAmounts(netBase, quoteCost);
        }

        BigDecimal reduction = sameCurrency(exchangeOrder.getFeeCcy(), properties.getBaseCcy())
                ? filled.subtract(amounts.fee())
                : filled;
        return new CumulativeSpotAmounts(reduction, BigDecimal.ZERO);
    }

    private static FillAmounts fillAmounts(OrderInfoResp order) {
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

    private static Instant exchangeUpdatedAt(OrderInfoResp order) {
        String value = firstText(order.getUTime(), order.getFillTime());
        if (value == null) {
            return Instant.now();
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (RuntimeException ignored) {
            return Instant.now();
        }
    }

    private static void verifyIdentity(TradingOrder localOrder, OrderInfoResp exchangeOrder) {
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

    private record FillAmounts(
            BigDecimal filledSize,
            BigDecimal averagePrice,
            BigDecimal fee
    ) {
    }

    private record CumulativeSpotAmounts(
            BigDecimal positionQuantity,
            BigDecimal quoteCost
    ) {
    }
}
