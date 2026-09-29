package com.trade.trading.domain.backtest;

import com.trade.trading.domain.model.TradingAction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Performance measures computed solely from completed trades and equity observations. */
public final class BacktestStatistics {
    public static List<BacktestEquityPoint> withDrawdowns(List<BacktestEquityPoint> source) {
        List<BacktestEquityPoint> result = new ArrayList<>(source.size());
        BigDecimal high = BigDecimal.ZERO;
        for (BacktestEquityPoint point : source) {
            if (point.equity().compareTo(high) > 0) {
                high = point.equity();
            }
            BigDecimal drawdown = high.signum() == 0
                    ? BigDecimal.ZERO
                    : high.subtract(point.equity()).divide(high, 10, RoundingMode.HALF_UP);
            result.add(new BacktestEquityPoint(
                    point.candleTimestamp(),
                    point.markPrice(),
                    point.cash(),
                    point.baseAmount(),
                    point.equity(),
                    drawdown
            ));
        }
        return List.copyOf(result);
    }
    public static TradeStatistics statistics(List<BacktestTrade> trades) {
        int closedTrades = 0;
        int wins = 0;
        int losses = 0;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        for (BacktestTrade trade : trades) {
            if (trade.action() != TradingAction.SELL) {
                continue;
            }
            closedTrades++;
            if (trade.realizedPnl().signum() > 0) {
                wins++;
                grossProfit = grossProfit.add(trade.realizedPnl());
            } else if (trade.realizedPnl().signum() < 0) {
                losses++;
                grossLoss = grossLoss.add(trade.realizedPnl().abs());
            }
        }
        BigDecimal winRate = closedTrades == 0
                ? BigDecimal.ZERO
                : new BigDecimal(wins).divide(new BigDecimal(closedTrades), 10, RoundingMode.HALF_UP);
        BigDecimal profitFactor = grossLoss.signum() == 0
                ? null
                : grossProfit.divide(grossLoss, 10, RoundingMode.HALF_UP);
        return new TradeStatistics(closedTrades, wins, losses, winRate, profitFactor);
    }
    public static BigDecimal maxDrawdown(List<BacktestEquityPoint> equityCurve) {
        return equityCurve.stream()
                .map(BacktestEquityPoint::drawdown)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }
    public record TradeStatistics(
            int closedTrades,
            int wins,
            int losses,
            BigDecimal winRate,
            BigDecimal profitFactor
    ) {
    }
}
