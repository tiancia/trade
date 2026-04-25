package com.trade.trading;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.OkxApi;
import com.trade.dto.AccountBalanceReq;
import com.trade.dto.AccountBalanceResp;
import com.trade.dto.BalanceDetail;
import com.trade.dto.CandleResp;
import com.trade.dto.CandlesReq;
import com.trade.dto.FillResp;
import com.trade.dto.FillsReq;
import com.trade.dto.InstrumentInfoReq;
import com.trade.dto.InstrumentInfoResp;
import com.trade.dto.OrderBookReq;
import com.trade.dto.OrderBookResp;
import com.trade.dto.OrderHistoryReq;
import com.trade.dto.OrderInfoResp;
import com.trade.dto.PendingOrdersReq;
import com.trade.dto.TickerReq;
import com.trade.dto.TickerResp;
import org.springframework.stereotype.Component;

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
        List<CandleResp> candles1m = okxApi.getCandles(new CandlesReq()
                .setInstId(properties.getInstId())
                .setBar("1m")
                .setLimit(String.valueOf(properties.getOneMinuteCandleLimit()))).getData();
        List<CandleResp> candles5m = okxApi.getCandles(new CandlesReq()
                .setInstId(properties.getInstId())
                .setBar("5m")
                .setLimit(String.valueOf(properties.getFiveMinuteCandleLimit()))).getData();
        AccountBalanceResp balance = OkxResponses.requireFirst(
                okxApi.getAccountBalance(new AccountBalanceReq()
                        .setCcy(properties.getBaseCcy() + "," + properties.getQuoteCcy())),
                "account balance"
        );
        List<OrderInfoResp> pendingOrders = okxApi.getPendingOrders(new PendingOrdersReq()
                .setInstType(properties.getInstType())
                .setInstId(properties.getInstId())
                .setLimit(String.valueOf(properties.getRecentOrderLimit()))).getData();
        List<OrderInfoResp> recentOrders = okxApi.getOrderHistory(new OrderHistoryReq()
                .setInstType(properties.getInstType())
                .setInstId(properties.getInstId())
                .setLimit(String.valueOf(properties.getRecentOrderLimit()))).getData();
        List<FillResp> recentFills = okxApi.getFills(new FillsReq()
                .setInstType(properties.getInstType())
                .setInstId(properties.getInstId())
                .setLimit(String.valueOf(properties.getRecentFillLimit()))).getData();

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
        List<CandleResp> candles = okxApi.getCandles(new CandlesReq()
                .setInstId(properties.getInstId())
                .setBar("1m")
                .setLimit(String.valueOf(properties.getOneMinuteCandleLimit()))).getData();
        return candles == null ? List.of() : candles;
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
        parameters.put("localTradingState", tradingState);
        parameters.put("derived", buildDerived(ticker, candles1m, baseBalance, quoteBalance, tradingState));
        return parameters;
    }

    private Map<String, Object> buildDerived(
            TickerResp ticker,
            List<CandleResp> candles1m,
            BalanceDetail baseBalance,
            BalanceDetail quoteBalance,
            TradingState tradingState
    ) {
        Map<String, Object> derived = new LinkedHashMap<>();
        java.math.BigDecimal last = TradingMath.decimal(ticker.getLast());
        java.math.BigDecimal bid = TradingMath.decimal(ticker.getBidPx());
        java.math.BigDecimal ask = TradingMath.decimal(ticker.getAskPx());
        derived.put("availableBase", TradingMath.decimal(baseBalance == null ? null : baseBalance.getAvailBal()));
        derived.put("availableQuote", TradingMath.decimal(quoteBalance == null ? null : quoteBalance.getAvailBal()));
        if (bid.signum() > 0 && ask.signum() > 0) {
            derived.put("spreadPercent", TradingMath.percentChange(ask, bid));
        }
        if (candles1m != null && candles1m.size() >= 5) {
            java.math.BigDecimal fiveMinuteBase = TradingMath.decimal(candles1m.get(4).getClose());
            derived.put("fiveMinutePriceChangePercent", TradingMath.percentChange(last, fiveMinuteBase));
        }
        if (tradingState != null && tradingState.hasTrackedPosition()) {
            derived.put("trackedPositionUnrealizedPnlPercent",
                    TradingMath.percentChange(last, tradingState.getAverageCost()));
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
