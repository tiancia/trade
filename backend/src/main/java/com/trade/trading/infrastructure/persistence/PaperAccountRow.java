package com.trade.trading.infrastructure.persistence;

import lombok.Data;
import lombok.experimental.Accessors;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class PaperAccountRow {
    private Long id;
    private String sessionId;
    private String instId;
    private String quoteCcy;
    private String status;
    private BigDecimal initialCash;
    private BigDecimal cash;
    private BigDecimal positionQuantity;
    private BigDecimal positionCost;
    private BigDecimal realizedPnl;
    private BigDecimal totalFeesQuote;
    private BigDecimal lastPrice;
    private Instant valuationAt;
    private long version;
    private String riskStateJson;
    private Instant createdAt;
    private Instant updatedAt;
}
