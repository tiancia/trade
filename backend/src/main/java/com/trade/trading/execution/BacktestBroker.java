package com.trade.trading.execution;

import com.trade.client.okx.dto.CandleResp;
import com.trade.common.support.TradingMath;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;

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
    private BigDecimal positionCost = BigDecimal.ZERO;
    private BigDecimal averageCost = BigDecimal.ZERO;
    private BigDecimal totalFees = BigDecimal.ZERO;
    private BigDecimal realizedPnl = BigDecimal.ZERO;
    private final List<BacktestTrade> trades = new ArrayList<>();

    public BacktestBroker(BigDecimal initialCash, BigDecimal feeRate, BigDecimal slippageRate) {
        this.cash = requirePositive(initialCash, "initialCash");
        this.feeRate = requireRate(feeRate, "feeRate");
        this.slippageRate = requireRate(slippageRate, "slippageRate");
    }

    public BacktestTrade execute(StrategyDecision decision, CandleResp fillCandle) {
        if (decision == null || decision.isHold() || fillCandle == null) {
            return null;
        }
        if (decision.getAction() == TradingAction.BUY || decision.getAction() == TradingAction.OPEN_LONG) {
            return buy(decision, fillCandle, open(fillCandle), FillPriceSource.OPEN);
        }
        if (decision.getAction() == TradingAction.SELL || decision.getAction() == TradingAction.CLOSE_LONG) {
            return sell(decision, fillCandle, open(fillCandle), FillPriceSource.OPEN);
        }
        return null;
    }

    /** Liquidates the remaining long position against the final candle close. */
    public BacktestTrade closePosition(String strategyId, CandleResp fillCandle, String reason) {
        if (base.signum() <= 0 || fillCandle == null) {
            return null;
        }
        StrategyDecision decision = new StrategyDecision()
                .setStrategyId(strategyId)
                .setAction(TradingAction.SELL)
                .setSellBaseAmount(base)
                .setReason(reason);
        return sell(decision, fillCandle, close(fillCandle), FillPriceSource.CLOSE);
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

    public BigDecimal getPositionCost() {
        return positionCost;
    }

    public BigDecimal getTotalFees() {
        return totalFees;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public BigDecimal unrealizedPnl(BigDecimal markPrice) {
        return base.multiply(zeroIfNull(markPrice)).subtract(positionCost);
    }

    public List<BacktestTrade> trades() {
        return List.copyOf(trades);
    }

    private BacktestTrade buy(
            StrategyDecision decision,
            CandleResp fillCandle,
            BigDecimal referencePrice,
            FillPriceSource fillPriceSource
    ) {
        BigDecimal requestedQuote = zeroIfNull(decision.getBuyQuoteAmount());
        BigDecimal quote = requestedQuote.signum() > 0 ? requestedQuote.min(cash) : cash;
        if (quote.signum() <= 0) {
            return null;
        }
        BigDecimal price = referencePrice.multiply(BigDecimal.ONE.add(slippageRate));
        if (price.signum() <= 0) {
            return null;
        }
        BigDecimal spend = quote.multiply(BigDecimal.ONE.add(feeRate)).min(cash);
        BigDecimal effectiveQuote = spend.divide(BigDecimal.ONE.add(feeRate), 18, RoundingMode.DOWN);
        BigDecimal fee = effectiveQuote.multiply(feeRate);
        BigDecimal baseBought = effectiveQuote.divide(price, 18, RoundingMode.DOWN);
        cash = cash.subtract(effectiveQuote).subtract(fee);
        base = base.add(baseBought);
        positionCost = positionCost.add(effectiveQuote).add(fee);
        averageCost = positionCost.divide(base, 18, RoundingMode.HALF_UP);
        totalFees = totalFees.add(fee);
        BacktestTrade trade = trade(
                decision,
                fillCandle,
                TradingAction.BUY,
                price,
                baseBought,
                effectiveQuote,
                fee,
                BigDecimal.ZERO,
                fillPriceSource
        );
        trades.add(trade);
        return trade;
    }

    private BacktestTrade sell(
            StrategyDecision decision,
            CandleResp fillCandle,
            BigDecimal referencePrice,
            FillPriceSource fillPriceSource
    ) {
        BigDecimal requestedBase = zeroIfNull(decision.getSellBaseAmount());
        BigDecimal sellBase = requestedBase.signum() > 0 ? requestedBase.min(base) : base;
        if (sellBase.signum() <= 0) {
            return null;
        }
        BigDecimal price = referencePrice.multiply(BigDecimal.ONE.subtract(slippageRate));
        if (price.signum() <= 0) {
            return null;
        }
        BigDecimal baseBefore = base;
        BigDecimal allocatedCost = sellBase.compareTo(baseBefore) == 0
                ? positionCost
                : positionCost.multiply(sellBase).divide(baseBefore, 18, RoundingMode.HALF_UP);
        BigDecimal grossQuote = sellBase.multiply(price);
        BigDecimal fee = grossQuote.multiply(feeRate);
        BigDecimal tradePnl = grossQuote.subtract(fee).subtract(allocatedCost);
        cash = cash.add(grossQuote).subtract(fee);
        base = base.subtract(sellBase);
        positionCost = positionCost.subtract(allocatedCost);
        totalFees = totalFees.add(fee);
        realizedPnl = realizedPnl.add(tradePnl);
        if (base.signum() <= 0) {
            base = BigDecimal.ZERO;
            positionCost = BigDecimal.ZERO;
            averageCost = BigDecimal.ZERO;
        } else {
            averageCost = positionCost.divide(base, 18, RoundingMode.HALF_UP);
        }
        BacktestTrade trade = trade(
                decision,
                fillCandle,
                TradingAction.SELL,
                price,
                sellBase,
                grossQuote,
                fee,
                tradePnl,
                fillPriceSource
        );
        trades.add(trade);
        return trade;
    }

    private BacktestTrade trade(
            StrategyDecision decision,
            CandleResp fillCandle,
            TradingAction action,
            BigDecimal price,
            BigDecimal baseAmount,
            BigDecimal quoteAmount,
            BigDecimal fee,
            BigDecimal tradePnl,
            FillPriceSource fillPriceSource
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
                decision.getReason(),
                fillPriceSource,
                tradePnl,
                cash,
                base
        );
    }

    private static BigDecimal open(CandleResp candle) {
        return TradingMath.decimal(candle.getOpen());
    }

    private static BigDecimal close(CandleResp candle) {
        return TradingMath.decimal(candle.getClose());
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static BigDecimal requireRate(BigDecimal value, String name) {
        BigDecimal normalized = zeroIfNull(value);
        if (normalized.signum() < 0 || normalized.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(name + " must be greater than or equal to 0 and less than 1");
        }
        return normalized;
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
            String reason,
            FillPriceSource fillPriceSource,
            BigDecimal realizedPnl,
            BigDecimal cashAfter,
            BigDecimal baseAfter
    ) {
        public BacktestTrade withRunId(String value) {
            return new BacktestTrade(
                    value,
                    strategyId,
                    action,
                    timestamp,
                    price,
                    baseAmount,
                    quoteAmount,
                    fee,
                    reason,
                    fillPriceSource,
                    realizedPnl,
                    cashAfter,
                    baseAfter
            );
        }
    }

    public enum FillPriceSource {
        OPEN,
        CLOSE
    }
}
