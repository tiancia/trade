package com.trade.trading.infrastructure.broker;

import com.trade.client.okx.dto.CandleResp;
import com.trade.common.support.TradingMath;
import com.trade.trading.domain.backtest.SimulatedPortfolio;
import com.trade.trading.domain.backtest.BacktestTrade;
import com.trade.trading.domain.model.StrategyDecision;
import java.math.BigDecimal;
import java.util.List;
/** Adapts provider candle data to the domain simulation portfolio. */
public class BacktestBroker {
    private final SimulatedPortfolio portfolio;
    public BacktestBroker(BigDecimal initialCash, BigDecimal feeRate, BigDecimal slippageRate) {
        portfolio = new SimulatedPortfolio(initialCash, feeRate, slippageRate);
    }
    public BacktestTrade execute(StrategyDecision decision, CandleResp candle) { return portfolio.execute(decision, candle(candle)); }
    public BacktestTrade closePosition(String strategyId, CandleResp candle, String reason) { return portfolio.closePosition(strategyId, candle(candle), reason); }
    public BigDecimal equity(BigDecimal mark) { return portfolio.equity(mark); }
    public BigDecimal unrealizedPnl(BigDecimal mark) { return portfolio.unrealizedPnl(mark); }
    public List<BacktestTrade> trades() { return portfolio.trades(); }
    public BigDecimal getCash() { return portfolio.getCash(); }
    public BigDecimal getBase() { return portfolio.getBase(); }
    public BigDecimal getAverageCost() { return portfolio.getAverageCost(); }
    public BigDecimal getPositionCost() { return portfolio.getPositionCost(); }
    public BigDecimal getTotalFees() { return portfolio.getTotalFees(); }
    public BigDecimal getRealizedPnl() { return portfolio.getRealizedPnl(); }
    private static SimulatedPortfolio.FillCandle candle(CandleResp candle) {
        return candle == null ? null : new SimulatedPortfolio.FillCandle(candle.getTs(),
                TradingMath.decimal(candle.getOpen()), TradingMath.decimal(candle.getClose()));
    }
}
