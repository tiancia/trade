package com.trade.trading.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Calendar-day boundaries shared by the advisor's goal, evidence and review window. */
public record TradingDay(LocalDate date, ZoneId zone, Instant start, Instant end) {
    public static TradingDay at(Instant now, ZoneId zone) {
        LocalDate date = now.atZone(zone).toLocalDate();
        return new TradingDay(date, zone, date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    public TradingDay previous() {
        return at(start.minusNanos(1), zone);
    }

    public Instant nextReviewAt(Instant now, long intervalMs) {
        Instant next = now.plusMillis(intervalMs);
        return next.isBefore(end) ? next : end;
    }
}
