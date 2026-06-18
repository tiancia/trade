package com.trade.trading.execution;

import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.common.support.TradingMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BacktestBroker {
    private final BigDecimal feeRate;
    private final BigDecimal slippageRate;
    private BigDecimal cash;
    private BigDecimal base = BigDecimal.ZERO;
    private BigDecimal averageCost = BigDecimal.ZERO;
    private final List<BacktestTrade> trades = new ArrayList<>();

    public BacktestBroker(BigDecimal initialCash, BigDecimal feeRate, BigDecimal slippageRate) {
        this.cash = zeroIfNull(initialCash);
        this.feeRate = zeroIfNull(feeRate);
        this.slippageRate = zeroIfNull(slippageRate);
    }

    public BacktestTrade execute(StrategyDecision decision, CandleResp fillCandle) {
        if (decision == null || decision.isHold() || fillCandle == null) {
            return null;
        }
        if (decision.getAction() == TradingAction.BUY || decision.getAction() == TradingAction.OPEN_LONG) {
            return buy(decision, fillCandle);
        }
        if (decision.getAction() == TradingAction.SELL || decision.getAction() == TradingAction.CLOSE_LONG) {
            return sell(decision, fillCandle);
        }
        return null;
    }

    public BigDecimal equity(BigDecimal markPrice) {
        return cash.add(base.multiply(zeroIfNull(markPrice)));
    }

    public BigDecimal getCash() {
        return cash;
    }

    public BigDecimal getBase() {
        return base;
    }

    public BigDecimal getAverageCost() {
        return averageCost;
    }

    public List<BacktestTrade> trades() {
        return List.copyOf(trades);
    }

    private BacktestTrade buy(StrategyDecision decision, CandleResp fillCandle) {
        BigDecimal requestedQuote = zeroIfNull(decision.getBuyQuoteAmount());
        BigDecimal quote = requestedQuote.signum() > 0 ? requestedQuote.min(cash) : cash;
        if (quote.signum() <= 0) {
            return null;
        }
        BigDecimal price = open(fillCandle).multiply(BigDecimal.ONE.add(slippageRate));
        if (price.signum() <= 0) {
            return null;
        }
        BigDecimal spend = quote.multiply(BigDecimal.ONE.add(feeRate)).min(cash);
        BigDecimal effectiveQuote = spend.divide(BigDecimal.ONE.add(feeRate), 18, RoundingMode.DOWN);
        BigDecimal fee = effectiveQuote.multiply(feeRate);
        BigDecimal baseBought = effectiveQuote.divide(price, 18, RoundingMode.DOWN);
        BigDecimal previousBase = base;
        cash = cash.subtract(effectiveQuote).subtract(fee);
        base = base.add(baseBought);
        averageCost = previousBase.signum() <= 0
                ? price
                : previousBase.multiply(averageCost).add(baseBought.multiply(price))
                .divide(base, 18, RoundingMode.HALF_UP);
        BacktestTrade trade = trade(decision, fillCandle, TradingAction.BUY, price, baseBought, effectiveQuote, fee);
        trades.add(trade);
        return trade;
    }

    private BacktestTrade sell(StrategyDecision decision, CandleResp fillCandle) {
        BigDecimal requestedBase = zeroIfNull(decision.getSellBaseAmount());
        BigDecimal sellBase = requestedBase.signum() > 0 ? requestedBase.min(base) : base;
        if (sellBase.signum() <= 0) {
            return null;
        }
        BigDecimal price = open(fillCandle).multiply(BigDecimal.ONE.subtract(slippageRate));
        if (price.signum() <= 0) {
            return null;
        }
        BigDecimal grossQuote = sellBase.multiply(price);
        BigDecimal fee = grossQuote.multiply(feeRate);
        cash = cash.add(grossQuote).subtract(fee);
        base = base.subtract(sellBase);
        if (base.signum() <= 0) {
            base = BigDecimal.ZERO;
            averageCost = BigDecimal.ZERO;
        }
        BacktestTrade trade = trade(decision, fillCandle, TradingAction.SELL, price, sellBase, grossQuote, fee);
        trades.add(trade);
        return trade;
    }

    private static BacktestTrade trade(
            StrategyDecision decision,
            CandleResp fillCandle,
            TradingAction action,
            BigDecimal price,
            BigDecimal baseAmount,
            BigDecimal quoteAmount,
            BigDecimal fee
    ) {
        return new BacktestTrade(
                null,
                decision.getStrategyId(),
                action,
                Instant.ofEpochMilli(Long.parseLong(fillCandle.getTs())),
                price,
                baseAmount,
                quoteAmount,
                fee,
                decision.getReason()
        );
    }

    private static BigDecimal open(CandleResp candle) {
        return TradingMath.decimal(candle.getOpen());
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record BacktestTrade(
            String runId,
            String strategyId,
            TradingAction action,
            Instant timestamp,
            BigDecimal price,
            BigDecimal baseAmount,
            BigDecimal quoteAmount,
            BigDecimal fee,
            String reason
    ) {
    }
}
