package com.trade.trading.model;

import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.TickerResp;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@Accessors(chain = true)
public class TradingDecisionContext {
    private Map<String, Object> aiParameters;
    private String aiParametersJson;
    private TickerResp ticker;
    private AccountBalanceResp accountBalance;
    private BalanceDetail baseBalance;
    private BalanceDetail quoteBalance;
    private InstrumentInfoResp instrument;
    private TradingState tradingState;
}
