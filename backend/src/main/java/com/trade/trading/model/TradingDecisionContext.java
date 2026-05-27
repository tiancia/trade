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

@Data
@Accessors(chain = true)
public class TradingDecisionContext {
    private Map<String, Object> aiParameters;
    private String aiParametersJson;
    private TickerResp ticker;
    private OrderBookResp orderBook;
    private List<CandleResp> oneMinuteCandles;
    private List<CandleResp> fiveMinuteCandles;
    private AccountBalanceResp accountBalance;
    private BalanceDetail baseBalance;
    private BalanceDetail quoteBalance;
    private InstrumentInfoResp instrument;
    private List<OrderInfoResp> pendingOrders;
    private List<OrderInfoResp> recentOrders;
    private List<FillResp> recentFills;
    private List<PositionResp> positions;
    private TradingState tradingState;
}
