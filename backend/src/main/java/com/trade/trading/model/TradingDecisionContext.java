package com.trade.trading.model;

import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.FillResp;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.PositionResp;
import com.trade.client.okx.dto.TickerResp;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/** Complete market, account, and local-state input for one trading decision. */
@Data
@Accessors(chain = true)
public class TradingDecisionContext {
    /** Structured values sent to the AI client. */
    private Map<String, Object> aiParameters;
    /** Serialized copy of {@link #aiParameters} persisted for audit. */
    private String aiParametersJson;
    /** Latest public ticker. */
    private TickerResp ticker;
    /** Current public order-book snapshot. */
    private OrderBookResp orderBook;
    /** One-minute candles ordered newest first. */
    private List<CandleResp> oneMinuteCandles;
    /** Five-minute candles ordered newest first. */
    private List<CandleResp> fiveMinuteCandles;
    /** Full account balance response used by sizing and audit. */
    private AccountBalanceResp accountBalance;
    /** Balance detail for the configured base currency. */
    private BalanceDetail baseBalance;
    /** Balance detail for the configured quote currency. */
    private BalanceDetail quoteBalance;
    /** Exchange trading rules for the configured instrument. */
    private InstrumentInfoResp instrument;
    /** Currently open orders for the configured instrument. */
    private List<OrderInfoResp> pendingOrders;
    /** Recently completed or cancelled orders. */
    private List<OrderInfoResp> recentOrders;
    /** Recent trade fills used to reconcile execution. */
    private List<FillResp> recentFills;
    /** Current derivative positions; empty for spot instruments. */
    private List<PositionResp> positions;
    /** Locally persisted strategy, position-cost, and risk state. */
    private TradingState tradingState;
}
