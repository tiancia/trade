package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class OkxCandleCacheRow {
    private String instId;
    private String bar;
    private Long ts;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal vol;
    private BigDecimal volCcy;
    private BigDecimal volCcyQuote;
    private String confirm;
}
