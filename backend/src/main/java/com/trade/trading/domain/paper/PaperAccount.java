package com.trade.trading.domain.paper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** One isolated, quote-currency funded spot simulation; legacy/exchange balances are never seeds. */
public record PaperAccount(
        Long id, String sessionId, String instId, String quoteCcy, String status,
        BigDecimal initialCash, BigDecimal cash, BigDecimal positionQuantity, BigDecimal positionCost,
        BigDecimal realizedPnl, BigDecimal totalFeesQuote, BigDecimal lastPrice, Instant valuationAt, long version,
        Instant createdAt, Instant updatedAt) {

    public static PaperAccount start(String sessionId, String instId, String quoteCcy,
                                     BigDecimal initialCash, Instant now) {
        if (initialCash == null || initialCash.signum() <= 0
                || initialCash.compareTo(new BigDecimal("1000000000000")) > 0 || initialCash.scale() > 8) {
            throw new IllegalArgumentException("Initial funds must be positive, at most 1000000000000, with at most 8 decimals");
        }
        return new PaperAccount(null, sessionId, instId, quoteCcy, "ACTIVE", initialCash, initialCash,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, 0, now, now);
    }

    public BigDecimal averageCost() {
        return positionQuantity.signum() == 0 ? BigDecimal.ZERO
                : positionCost.divide(positionQuantity, 18, RoundingMode.HALF_UP);
    }

    public BigDecimal equity() {
        return lastPrice == null && positionQuantity.signum() > 0 ? null
                : scale(cash.add(positionQuantity.multiply(lastPrice == null ? BigDecimal.ZERO : lastPrice)));
    }

    public BigDecimal unrealizedPnl() {
        return lastPrice == null && positionQuantity.signum() > 0 ? null
                : scale(positionQuantity.multiply(lastPrice == null ? BigDecimal.ZERO : lastPrice).subtract(positionCost));
    }

    public BigDecimal totalReturnPercent() {
        return equity() == null ? null
                : equity().subtract(initialCash).multiply(new BigDecimal("100"))
                .divide(initialCash, 8, RoundingMode.HALF_UP);
    }

    public PaperAccount mark(BigDecimal price, Instant marketTime, Instant now) {
        positive(price, "Market price");
        return new PaperAccount(id, sessionId, instId, quoteCcy, status, initialCash, cash, positionQuantity,
                positionCost, realizedPnl, totalFeesQuote, price, marketTime, version, createdAt, now);
    }

    public FillResult fill(String side, BigDecimal size, BigDecimal price, BigDecimal feeRate, Instant now) {
        positive(size, "Order size");
        positive(price, "Market price");
        if (feeRate == null || feeRate.signum() < 0 || feeRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("Fee rate must be in [0, 1)");
        }
        if ("BUY".equals(side)) {
            if (size.compareTo(cash) > 0) throw new IllegalArgumentException("Insufficient simulated cash");
            BigDecimal gross = size.divide(price, 18, RoundingMode.DOWN);
            BigDecimal feeBase = scale(gross.multiply(feeRate));
            BigDecimal net = gross.subtract(feeBase);
            positive(net, "Net fill quantity");
            BigDecimal feeQuote = scale(feeBase.multiply(price));
            return new FillResult(next(cash.subtract(size), positionQuantity.add(net), positionCost.add(size),
                    realizedPnl, totalFeesQuote.add(feeQuote), price, version + 1, now),
                    gross, net, size.negate(), feeQuote, BigDecimal.ZERO);
        }
        if (!"SELL".equals(side)) throw new IllegalArgumentException("Only spot BUY/SELL simulation is supported");
        if (size.compareTo(positionQuantity) > 0) throw new IllegalArgumentException("Insufficient simulated position");
        BigDecimal grossQuote = scale(size.multiply(price));
        BigDecimal feeQuote = scale(grossQuote.multiply(feeRate));
        BigDecimal proceeds = grossQuote.subtract(feeQuote);
        BigDecimal releasedCost = size.compareTo(positionQuantity) == 0 ? positionCost
                : positionCost.multiply(size).divide(positionQuantity, 18, RoundingMode.HALF_UP);
        BigDecimal profit = proceeds.subtract(releasedCost);
        return new FillResult(next(cash.add(proceeds), positionQuantity.subtract(size),
                positionCost.subtract(releasedCost), realizedPnl.add(profit), totalFeesQuote.add(feeQuote),
                price, version + 1, now), size, size.negate(), proceeds, feeQuote, profit);
    }

    private PaperAccount next(BigDecimal nextCash, BigDecimal quantity, BigDecimal cost, BigDecimal pnl,
                              BigDecimal fees, BigDecimal price, long nextVersion, Instant now) {
        return new PaperAccount(id, sessionId, instId, quoteCcy, status, initialCash, nextCash, quantity,
                cost, pnl, fees, price, valuationAt, nextVersion, createdAt, now);
    }

    private static BigDecimal scale(BigDecimal value) { return value.setScale(18, RoundingMode.HALF_UP); }
    private static void positive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    public record FillResult(PaperAccount account, BigDecimal grossBase, BigDecimal positionDelta,
                             BigDecimal cashDelta, BigDecimal feeQuote, BigDecimal realizedPnl) {}
}
