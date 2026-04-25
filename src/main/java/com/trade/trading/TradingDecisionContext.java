package com.trade.trading;

import com.trade.dto.AccountBalanceResp;
import com.trade.dto.BalanceDetail;
import com.trade.dto.InstrumentInfoResp;
import com.trade.dto.TickerResp;
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
