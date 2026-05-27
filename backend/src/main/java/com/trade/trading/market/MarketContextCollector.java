package com.trade.trading.market;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.AccountBalanceReq;
import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.client.okx.dto.FillResp;
import com.trade.client.okx.dto.FillsReq;
import com.trade.client.okx.dto.InstrumentInfoReq;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.OrderBookReq;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.OrderHistoryReq;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.PendingOrdersReq;
import com.trade.client.okx.dto.PositionResp;
import com.trade.client.okx.dto.PositionsReq;
import com.trade.client.okx.dto.TickerReq;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingState;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.support.TradingMath;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects OKX market, account, and local state into one strategy evaluation
 * context. The same context is later reused by risk checks and execution.
 */
@Component
public class MarketContextCollector {
    private final OkxApi okxApi;
    private final TradingProperties properties;
    private final TradingStateRepository stateRepository;
    private final ObjectMapper objectMapper;
    // Instrument rules change rarely and are needed on every decision, so keep
    // a small process-local cache instead of hitting OKX each cycle.
    private volatile InstrumentInfoResp cachedInstrument;

    public MarketContextCollector(
            OkxApi okxApi,
            TradingProperties properties,
            TradingStateRepository stateRepository
    ) {
        this.okxApi = okxApi;
        this.properties = properties;
        this.stateRepository = stateRepository;
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public TradingDecisionContext collect(TradingTrigger trigger) {
        TickerResp ticker = OkxResponses.requireFirst(
                okxApi.getTicker(new TickerReq().setInstId(properties.getInstId())),
                "ticker"
        );
        OrderBookResp orderBook = OkxResponses.requireFirst(
                okxApi.getOrderBook(new OrderBookReq()
                        .setInstId(properties.getInstId())
                        .setSz(String.valueOf(properties.getOrderBookDepth()))),
                "order book"
        );
        List<CandleResp> candles1m = OkxResponses.data(
                okxApi.getCandles(new CandlesReq()
                        .setInstId(properties.getInstId())
                        .setBar("1m")
                        .setLimit(String.valueOf(properties.getOneMinuteCandleLimit()))),
                "1m candles"
        );
        List<CandleResp> candles5m = OkxResponses.data(
                okxApi.getCandles(new CandlesReq()
                        .setInstId(properties.getInstId())
                        .setBar("5m")
                        .setLimit(String.valueOf(properties.getFiveMinuteCandleLimit()))),
                "5m candles"
        );
        AccountBalanceResp balance = OkxResponses.requireFirst(
                okxApi.getAccountBalance(new AccountBalanceReq()
                        .setCcy(properties.getBaseCcy() + "," + properties.getQuoteCcy())),
                "account balance"
        );
        List<OrderInfoResp> pendingOrders = OkxResponses.data(
                okxApi.getPendingOrders(new PendingOrdersReq()
                        .setInstType(properties.getInstType())
                        .setInstId(properties.getInstId())
                        .setLimit(String.valueOf(properties.getRecentOrderLimit()))),
                "pending orders"
        );
        List<OrderInfoResp> recentOrders = OkxResponses.data(
                okxApi.getOrderHistory(new OrderHistoryReq()
                        .setInstType(properties.getInstType())
                        .setInstId(properties.getInstId())
                        .setLimit(String.valueOf(properties.getRecentOrderLimit()))),
                "recent orders"
        );
        List<FillResp> recentFills = OkxResponses.data(
                okxApi.getFills(new FillsReq()
                        .setInstType(properties.getInstType())
                        .setInstId(properties.getInstId())
                        .setLimit(String.valueOf(properties.getRecentFillLimit()))),
                "recent fills"
        );
        List<PositionResp> positions = collectPositions();

        InstrumentInfoResp instrument = getInstrument();
        TradingState tradingState = stateRepository.getState();
        BalanceDetail baseBalance = findBalance(balance, properties.getBaseCcy());
        BalanceDetail quoteBalance = findBalance(balance, properties.getQuoteCcy());

        Map<String, Object> parameters = buildParameters(
                trigger,
                ticker,
                orderBook,
                candles1m,
                candles5m,
                balance,
                baseBalance,
                quoteBalance,
                pendingOrders,
                recentOrders,
                recentFills,
                positions,
                instrument,
                tradingState
        );

        return new TradingDecisionContext()
                .setAiParameters(parameters)
                .setAiParametersJson(toJson(parameters))
                .setTicker(ticker)
                .setOrderBook(orderBook)
                .setOneMinuteCandles(candles1m)
                .setFiveMinuteCandles(candles5m)
                .setAccountBalance(balance)
                .setBaseBalance(baseBalance)
                .setQuoteBalance(quoteBalance)
                .setInstrument(instrument)
                .setPendingOrders(pendingOrders)
                .setRecentOrders(recentOrders)
                .setRecentFills(recentFills)
                .setPositions(positions)
                .setTradingState(tradingState);
    }

    public List<CandleResp> getOneMinuteCandles() {
        return OkxResponses.data(
                okxApi.getCandles(new CandlesReq()
                        .setInstId(properties.getInstId())
                        .setBar("1m")
                        .setLimit(String.valueOf(properties.getOneMinuteCandleLimit()))),
                "1m candles"
        );
    }

    public TickerResp getTicker() {
        return OkxResponses.requireFirst(
                okxApi.getTicker(new TickerReq().setInstId(properties.getInstId())),
                "ticker"
        );
    }

    private InstrumentInfoResp getInstrument() {
        InstrumentInfoResp instrument = cachedInstrument;
        if (instrument != null) {
            return instrument;
        }

        instrument = OkxResponses.requireFirst(
                okxApi.getInstrumentInfo(new InstrumentInfoReq()
                        .setInstType(properties.getInstType())
                        .setInstId(properties.getInstId())),
                "instrument"
        );
        cachedInstrument = instrument;
        return instrument;
    }

    private List<PositionResp> collectPositions() {
        if (properties.isSpotInstrument()) {
            return List.of();
        }
        return OkxResponses.data(
                okxApi.getPositions(new PositionsReq()
                        .setInstType(properties.getInstType())
                        .setInstId(properties.getInstId())),
                "positions"
        );
    }

    private Map<String, Object> buildParameters(
            TradingTrigger trigger,
            TickerResp ticker,
            OrderBookResp orderBook,
            List<CandleResp> candles1m,
            List<CandleResp> candles5m,
            AccountBalanceResp balance,
            BalanceDetail baseBalance,
            BalanceDetail quoteBalance,
            List<OrderInfoResp> pendingOrders,
            List<OrderInfoResp> recentOrders,
            List<FillResp> recentFills,
            List<PositionResp> positions,
            InstrumentInfoResp instrument,
            TradingState tradingState
    ) {
        // LinkedHashMap keeps logs and persisted prompt JSON in a predictable
        // order, which is useful when comparing decision runs.
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("timestamp", Instant.now().toString());
        parameters.put("instrumentId", properties.getInstId());
        parameters.put("instrumentType", properties.getInstType());
        parameters.put("tradeMode", properties.getTdMode());
        parameters.put("positionMode", properties.getPositionMode());
        parameters.put("allowedActions", allowedActions());
        parameters.put("strategyProfile", properties.getStrategy());
        parameters.put("decisionPolicy", buildDecisionPolicy());
        parameters.put("trigger", trigger);
        parameters.put("riskLimits", buildRiskLimits());
        parameters.put("tradingCosts", buildTradingCosts(ticker));
        parameters.put("ticker", ticker);
        parameters.put("orderBookTop", orderBook);
        parameters.put("candles1mNewestFirst", candles1m == null ? List.of() : candles1m);
        parameters.put("candles5mNewestFirst", candles5m == null ? List.of() : candles5m);
        parameters.put("balances", Map.of(
                properties.getBaseCcy(), baseBalance == null ? Map.of() : baseBalance,
                properties.getQuoteCcy(), quoteBalance == null ? Map.of() : quoteBalance
        ));
        parameters.put("accountSummary", balance);
        parameters.put("pendingOrders", pendingOrders == null ? List.of() : pendingOrders);
        parameters.put("recentOrders", recentOrders == null ? List.of() : recentOrders);
        parameters.put("recentFills", recentFills == null ? List.of() : recentFills);
        parameters.put("positions", positions == null ? List.of() : positions);
        parameters.put("instrumentRules", instrument);
        parameters.put("localTradingState", buildLocalTradingState(tradingState));
        parameters.put("recentDecisionsNewestFirst",
                tradingState == null || tradingState.getRecentDecisions() == null
                        ? List.of()
                        : tradingState.getRecentDecisions());
        parameters.put("derived", buildDerived(ticker, candles1m, baseBalance, quoteBalance, tradingState));
        return parameters;
    }

    private Map<String, Object> buildRiskLimits() {
        TradingProperties.StrategyProperties strategy = properties.getStrategy();
        Map<String, Object> riskLimits = new LinkedHashMap<>();
        riskLimits.put("maxBuyQuoteAmountUsdt", properties.getMaxBuyQuoteAmount());
        riskLimits.put("maxSellPositionRatio", properties.getMaxSellPositionRatio());
        riskLimits.put("maxDerivativeOrderSize", properties.getMaxDerivativeOrderSize());
        riskLimits.put("allowShort", properties.isShortEnabled());
        riskLimits.put("priceMoveTriggerPercent", properties.getPriceMoveTriggerPercent());
        riskLimits.put("volumeSpikeMultiplier", properties.getVolumeSpikeMultiplier());
        riskLimits.put("floatingLossTriggerPercent", properties.getFloatingLossTriggerPercent());
        riskLimits.put("riskControl", properties.getRisk());
        if (strategy != null) {
            riskLimits.put("maxDrawdownRatio", strategy.getMaxDrawdownRatio());
            riskLimits.put("minRiskRewardRatio", strategy.getMinRiskRewardRatio());
            riskLimits.put("minWinProbability", strategy.getMinWinProbability());
            riskLimits.put("minConfidence", strategy.getMinConfidence());
            riskLimits.put("minWinConfidenceScore", strategy.getMinWinConfidenceScore());
        }
        return riskLimits;
    }

    private Map<String, Object> buildDecisionPolicy() {
        TradingProperties.StrategyProperties strategy = properties.getStrategy();
        Map<String, Object> policy = new LinkedHashMap<>();
        // These policy strings are intentionally passed to the AI alongside
        // numeric limits so the prompt and application-side guards match.
        policy.put("defaultAction", "HOLD");
        policy.put("objectivePriority", List.of(
                "1. Preserve capital and avoid avoidable drawdown.",
                "2. Avoid churn: do not trade unless the setup clears costs and configured edge thresholds.",
                "3. Compound account equity only through actions that remain consistent with the persistent thesis."
        ));
        if (strategy != null) {
            policy.put("nonHoldHardGates", List.of(
                    "objectiveAlignment must be PASS",
                    "winProbability must be at least " + strategy.getMinWinProbability(),
                    "confidence must be at least " + strategy.getMinConfidence(),
                    "winProbability * confidence must be at least " + strategy.getMinWinConfidenceScore(),
                    "riskRewardRatio must be at least " + strategy.getMinRiskRewardRatio(),
                    "expectedNetEdgePercent must be at least tradingCosts.minExpectedNetEdgePercent",
                    "strategyThesis, strategyInvalidation, strategyHorizon, and thesisChangeEvidence must be specific"
            ));
        }
        policy.put("executionGuard", "The application skips non-HOLD actions that fail the hard gates even if the AI selects them.");
        return policy;
    }

    private List<String> allowedActions() {
        if (properties.isSpotInstrument()) {
            return List.of("BUY", "HOLD", "SELL");
        }
        if (properties.isShortEnabled()) {
            return List.of("OPEN_LONG", "CLOSE_LONG", "OPEN_SHORT", "CLOSE_SHORT", "HOLD");
        }
        return List.of("OPEN_LONG", "CLOSE_LONG", "HOLD");
    }

    private static Map<String, Object> buildLocalTradingState(TradingState tradingState) {
        if (tradingState == null) {
            return Map.of();
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("trackedBaseAmount", tradingState.getTrackedBaseAmount());
        state.put("averageCost", tradingState.getAverageCost());
        state.put("updatedAt", tradingState.getUpdatedAt());
        state.put("hasTrackedPosition", tradingState.hasTrackedPosition());
        state.put("strategyState", tradingState.getStrategyState());
        state.put("riskState", tradingState.getRiskState());
        return state;
    }

    private Map<String, Object> buildTradingCosts(TickerResp ticker) {
        Map<String, Object> costs = new LinkedHashMap<>();
        BigDecimal takerFeeRate = properties.getTakerFeeRate();
        BigDecimal roundTripFeeRate = takerFeeRate.multiply(new BigDecimal("2"));
        costs.put("takerFeeRate", takerFeeRate);
        costs.put("estimatedRoundTripFeeRate", roundTripFeeRate);
        costs.put("minExpectedNetEdgePercent", properties.getMinExpectedNetEdgePercent());
        costs.put("units", "decimal ratio, 0.001 means 0.1%");

        BigDecimal bid = TradingMath.decimal(ticker == null ? null : ticker.getBidPx());
        BigDecimal ask = TradingMath.decimal(ticker == null ? null : ticker.getAskPx());
        if (bid.signum() > 0 && ask.signum() > 0) {
            BigDecimal spreadPercent = TradingMath.percentChange(ask, bid);
            costs.put("currentSpreadPercent", spreadPercent);
            costs.put("estimatedRoundTripTradingCostPercent", spreadPercent.add(roundTripFeeRate));
        } else {
            costs.put("estimatedRoundTripTradingCostPercent", roundTripFeeRate);
        }
        return costs;
    }

    private Map<String, Object> buildDerived(
            TickerResp ticker,
            List<CandleResp> candles1m,
            BalanceDetail baseBalance,
            BalanceDetail quoteBalance,
            TradingState tradingState
    ) {
        Map<String, Object> derived = new LinkedHashMap<>();
        BigDecimal last = TradingMath.decimal(ticker.getLast());
        BigDecimal bid = TradingMath.decimal(ticker.getBidPx());
        BigDecimal ask = TradingMath.decimal(ticker.getAskPx());
        derived.put("availableBase", TradingMath.decimal(baseBalance == null ? null : baseBalance.getAvailBal()));
        derived.put("availableQuote", TradingMath.decimal(quoteBalance == null ? null : quoteBalance.getAvailBal()));
        BigDecimal roundTripFeeRate = properties.getTakerFeeRate().multiply(new BigDecimal("2"));
        derived.put("estimatedRoundTripFeePercent", roundTripFeeRate);
        derived.put("minExpectedNetEdgePercent", properties.getMinExpectedNetEdgePercent());
        if (bid.signum() > 0 && ask.signum() > 0) {
            BigDecimal spreadPercent = TradingMath.percentChange(ask, bid);
            derived.put("spreadPercent", spreadPercent);
            derived.put("estimatedRoundTripTradingCostPercent", spreadPercent.add(roundTripFeeRate));
        } else {
            derived.put("estimatedRoundTripTradingCostPercent", roundTripFeeRate);
        }
        if (candles1m != null && candles1m.size() >= 5) {
            BigDecimal fiveMinuteBase = TradingMath.decimal(candles1m.get(4).getClose());
            derived.put("fiveMinutePriceChangePercent", TradingMath.percentChange(last, fiveMinuteBase));
        }
        if (tradingState != null && tradingState.hasTrackedPosition()) {
            // Use local cost basis, not only exchange balances, so the prompt
            // can reason about net profitability after estimated exit fees.
            derived.put("trackedPositionUnrealizedPnlPercent",
                    TradingMath.percentChange(last, tradingState.getAverageCost()));
            BigDecimal estimatedExitPriceAfterFee = last.multiply(BigDecimal.ONE.subtract(properties.getTakerFeeRate()));
            derived.put("trackedPositionUnrealizedPnlAfterEstimatedSellFeePercent",
                    TradingMath.percentChange(estimatedExitPriceAfterFee, tradingState.getAverageCost()));
            if (properties.getTakerFeeRate().compareTo(BigDecimal.ONE) < 0) {
                derived.put("trackedPositionBreakEvenSellPriceIncludingFee",
                        tradingState.getAverageCost().divide(
                                BigDecimal.ONE.subtract(properties.getTakerFeeRate()),
                                18,
                                java.math.RoundingMode.HALF_UP
                        ));
            }
        }
        return derived;
    }

    private BalanceDetail findBalance(AccountBalanceResp balance, String ccy) {
        if (balance == null || balance.getDetails() == null) {
            return null;
        }
        for (BalanceDetail detail : balance.getDetails()) {
            if (ccy.equalsIgnoreCase(detail.getCcy())) {
                return detail;
            }
        }
        return null;
    }

    private String toJson(Map<String, Object> parameters) {
        try {
            return objectMapper.writeValueAsString(parameters);
        } catch (Exception e) {
            throw new IllegalStateException("Serialize AI parameters failed", e);
        }
    }
}
