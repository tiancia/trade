package com.trade.trading.domain.order;

import lombok.Value;

/** Immutable observations used for identity and cumulative settlement validation. */
@Value
public class ExchangeOrderObservation {
    String ordId;
    String clOrdId;
    String instId;
    String side;
    String state;
    String rebate;
    String accFillSz;
    String fillSz;
    String avgPx;
    String fillPx;
    String fee;
    String feeCcy;
}
