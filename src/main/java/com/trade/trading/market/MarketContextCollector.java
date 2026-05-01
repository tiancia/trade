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
import com.trade.client.okx.dto.TickerReq;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.AiTradingProperties;
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

@Component
public class MarketContextCollector {
    private final OkxApi okxApi;
    private final AiTradingProperties properties;
    private final TradingStateRepository stateRepository;
    private final ObjectMapper objectMapper;
    private volatile InstrumentInfoResp cachedInstrument;

    public MarketContextCollector(
            OkxApi okxApi,
            AiTradingProperties properties,
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
                instrument,
                tradingState
        );

        return new TradingDecisionContext()
                .setAiParameters(parameters)
                .setAiParametersJson(toJson(parameters))
                .setTicker(ticker)
                .setAccountBalance(balance)
                .setBaseBalance(baseBalance)
                .setQuoteBalance(quoteBalance)
                .setInstrument(instrument)
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
            InstrumentInfoResp instrument,
            TradingState tradingState
    ) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("timestamp", Instant.now().toString());
        parameters.put("instrumentId", properties.getInstId());
        parameters.put("instrumentType", properties.getInstType());
        parameters.put("tradeMode", properties.getTdMode());
        parameters.put("allowedActions", List.of("BUY", "HOLD", "SELL"));
        parameters.put("trigger", trigger);
        parameters.put("riskLimits", Map.of(
                "maxBuyQuoteAmountUsdt", properties.getMaxBuyQuoteAmount(),
                "maxSellPositionRatio", properties.getMaxSellPositionRatio(),
                "priceMoveTriggerPercent", properties.getPriceMoveTriggerPercent(),
                "volumeSpikeMultiplier", properties.getVolumeSpikeMultiplier(),
                "floatingLossTriggerPercent", properties.getFloatingLossTriggerPercent()
        ));
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
        parameters.put("instrumentRules", instrument);
        parameters.put("localTradingState", buildLocalTradingState(tradingState));
        parameters.put("recentDecisionsNewestFirst",
                tradingState == null || tradingState.getRecentDecisions() == null
                        ? List.of()
                        : tradingState.getRecentDecisions());
        parameters.put("derived", buildDerived(ticker, candles1m, baseBalance, quoteBalance, tradingState));
        return parameters;
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
