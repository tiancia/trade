package com.trade.trading.application.order;

import com.trade.trading.domain.order.OrderIdentity;
import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Builds a stable business fingerprint and the deterministic OKX clOrdId. */
@Component
public class OrderIdempotencyKeyFactory {
    public String create(TradingProperties properties, StrategyDecision decision, TradingDecisionContext context,
                         TradingDecisionRecord record, String requestedSize) {
        var snapshot = new OrderIdentity.Snapshot(
                candles(context == null ? null : context.getOneMinuteCandles()),
                candles(context == null ? null : context.getFiveMinuteCandles()),
                context == null || context.getTicker() == null ? null : context.getTicker().getTs(),
                record == null ? null : record.getDecisionId());
        return new OrderIdentity().create(properties.getInstId(), decision, snapshot, requestedSize);
    }

    public String clientOrderId(String idempotencyKey, String action) {
        return new OrderIdentity().clientOrderId(idempotencyKey, action);
    }

    private static List<OrderIdentity.CandleIdentity> candles(List<CandleResp> candles) {
        return candles == null ? null : candles.stream().map(c -> c == null ? null
                : new OrderIdentity.CandleIdentity(c.getTs(), "1".equals(c.getConfirm()))).toList();
    }
}
