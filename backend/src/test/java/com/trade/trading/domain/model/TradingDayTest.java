package com.trade.trading.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class TradingDayTest {
    @Test void dailyReviewResetsAtLocalMidnightInsteadOfDriftingByStartupTime() {
        Instant now = Instant.parse("2026-10-28T15:59:50Z");
        TradingDay day = TradingDay.at(now, ZoneId.of("Asia/Shanghai"));
        assertEquals("2026-10-28", day.date().toString());
        assertEquals(Instant.parse("2026-10-27T16:00:00Z"), day.start());
        assertEquals(Instant.parse("2026-10-28T16:00:00Z"), day.nextReviewAt(now, 86400000));
        assertEquals(day.end(), day.nextReviewAt(now, 300000)); // A plan cannot apply on the following day.
        assertEquals(day.start(), day.previous().end());
        assertEquals("2026-10-29", TradingDay.at(day.end(), day.zone()).date().toString());
    }

    @Test void optionalIntradayReviewAndDaylightSavingUseCalendarBoundaries() {
        Instant now = Instant.parse("2026-10-28T01:00:00Z");
        TradingDay day = TradingDay.at(now, ZoneId.of("Asia/Shanghai"));
        assertEquals(now.plusSeconds(3600), day.nextReviewAt(now, 3600000));
        TradingDay shortDay = TradingDay.at(Instant.parse("2026-03-08T12:00:00Z"), ZoneId.of("America/New_York"));
        assertEquals(23, Duration.between(shortDay.start(), shortDay.end()).toHours());
        assertEquals(shortDay.start(), shortDay.previous().end());
    }
}
