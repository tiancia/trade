package com.trade.trading.application.order;

import com.trade.trading.domain.order.ExchangeOrderObservation;
import com.trade.trading.domain.order.OrderSettlementPolicy;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.order.OrderFill;
import com.trade.trading.domain.order.OrderSettlementResult;
import com.trade.trading.domain.order.OrderTransitionResult;
import com.trade.trading.domain.order.SpotFillApplication;
import com.trade.trading.domain.order.TradingOrder;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.application.port.TradingStateStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
    private final TradingStateStore stateRepository;
    private final RiskControlService riskControlService;
    private final TradingProperties properties;

    public OrderSettlementService(
            OrderLifecycleService lifecycleService,
            TradingStateStore stateRepository,
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
        var observation = new ExchangeOrderObservation(
                exchangeOrder.getOrdId(),
                exchangeOrder.getClOrdId(),
                exchangeOrder.getInstId(),
                exchangeOrder.getSide(),
                exchangeOrder.getState(),
                exchangeOrder.getRebate(),
                exchangeOrder.getAccFillSz(),
                exchangeOrder.getFillSz(),
                exchangeOrder.getAvgPx(),
                exchangeOrder.getFillPx(),
                exchangeOrder.getFee(),
                exchangeOrder.getFeeCcy());
        var policy = new OrderSettlementPolicy(properties.getBaseCcy(), properties.getQuoteCcy());
        var plan = policy.prepare(localOrder, observation);
        var amounts = plan.amounts();
        String exchangeState = plan.exchangeState();
        String exchangeOrderId = plan.exchangeOrderId();
        OrderFill fill = plan.fill();
        OrderTransitionResult transition = plan.target() == null
                ? new OrderTransitionResult(localOrder, false)
                : switch (plan.target()) {
                    case FILLED -> lifecycleService.markFilled(localOrder.getIdempotencyKey(), exchangeOrderId, fill);
                    case PARTIALLY_FILLED -> lifecycleService.markPartiallyFilled(localOrder.getIdempotencyKey(), exchangeOrderId, fill);
                    case CANCELED -> lifecycleService.markCanceled(localOrder.getIdempotencyKey(), exchangeOrderId, fill);
                    case ACCEPTED -> lifecycleService.markAccepted(localOrder.getIdempotencyKey(), exchangeOrderId);
                    default -> throw new IllegalStateException("Unexpected settlement target: " + plan.target());
                };
        SpotFillApplication application = SpotFillApplication.unchanged();
        if (properties.isSpotInstrument() && amounts.filledSize().signum() > 0) {
            var spotAmounts = policy.cumulativeSpotAmounts(localOrder, observation, amounts);
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

        return new OrderSettlementResult(transition.order(), plan.executionStatus(), application);
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstText(String first, String fallback) {
        return hasText(first) ? first : fallback;
    }

}
