package com.trade.polymarket.support;

import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.model.PolymarketOutcomeSnapshot;
import com.trade.common.support.TradingMath;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public final class PolymarketMarketFilters {
    private PolymarketMarketFilters() {
    }

    public static String marketTurnoverSkipReason(
            AiPolymarketProperties properties,
            String endDate,
            String volume24hr,
            String liquidity,
            Instant now
    ) {
        Optional<Instant> endInstant = parseMarketEndDate(endDate);
        if (isTimeFilterEnabled(properties)) {
            if (!hasText(endDate)) {
                return "market end date is missing";
            }
            if (endInstant.isEmpty()) {
                return "market end date is invalid";
            }
            long minutesToResolution = Duration.between(now, endInstant.get()).toMinutes();
            if (minutesToResolution <= 0) {
                return "market end date has passed";
            }
            if (properties.getMinTimeToResolutionMinutes() > 0
                    && minutesToResolution < properties.getMinTimeToResolutionMinutes()) {
                return "market resolves too soon";
            }
            long maxTimeToResolutionMinutes = maxTimeToResolutionMinutes(properties);
            if (maxTimeToResolutionMinutes > 0 && minutesToResolution > maxTimeToResolutionMinutes) {
                return "market resolves beyond configured short-term window";
            }
        }

        String volumeSkipReason = minValueSkipReason(
                "market 24h volume",
                volume24hr,
                properties.getMinMarketVolume24hr()
        );
        if (volumeSkipReason != null) {
            return volumeSkipReason;
        }
        return minValueSkipReason("market liquidity", liquidity, properties.getMinMarketLiquidity());
    }

    public static String outcomeLiquiditySkipReason(
            AiPolymarketProperties properties,
            PolymarketOutcomeSnapshot outcome
    ) {
        if (outcome == null) {
            return "outcome is null";
        }
        BigDecimal maxSpread = properties.getMaxOutcomeSpread();
        if (isPositive(maxSpread)) {
            BigDecimal bestBid = zeroIfNull(outcome.getBestBid());
            BigDecimal bestAsk = zeroIfNull(outcome.getBestAsk());
            if (!isPositive(bestBid) || !isPositive(bestAsk)) {
                return "outcome has no executable bid/ask";
            }
            if (bestAsk.compareTo(bestBid) < 0) {
                return "outcome order book is crossed";
            }
            BigDecimal spread = outcome.getSpread() == null ? bestAsk.subtract(bestBid) : outcome.getSpread();
            if (spread.compareTo(maxSpread) > 0) {
                return "outcome spread is wider than configured maximum";
            }
        }

        BigDecimal minAskLiquidity = properties.getMinOutcomeAskLiquidityUsdc();
        if (isPositive(minAskLiquidity)
                && zeroIfNull(outcome.getTopAskLiquidityUsdc()).compareTo(minAskLiquidity) < 0) {
            return "outcome top ask liquidity is below configured minimum";
        }
        return null;
    }

    public static Long timeToResolutionMinutes(String endDate, Instant now) {
        return parseMarketEndDate(endDate)
                .map(endInstant -> Duration.between(now, endInstant).toMinutes())
                .orElse(null);
    }

    public static Optional<Instant> parseMarketEndDate(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }
        String normalized = value.trim();
        try {
            return Optional.of(Instant.parse(normalized));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Optional.of(OffsetDateTime.parse(normalized).toInstant());
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Optional.of(ZonedDateTime.parse(normalized).toInstant());
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Optional.of(LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Optional.of(LocalDate.parse(normalized).atStartOfDay().toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isTimeFilterEnabled(AiPolymarketProperties properties) {
        return properties.isRequireMarketEndDate()
                || properties.getMinTimeToResolutionMinutes() > 0
                || properties.getMaxTimeToResolutionHours() > 0;
    }

    private static long maxTimeToResolutionMinutes(AiPolymarketProperties properties) {
        long hours = properties.getMaxTimeToResolutionHours();
        if (hours <= 0) {
            return 0L;
        }
        return hours > Long.MAX_VALUE / 60L ? Long.MAX_VALUE : hours * 60L;
    }

    private static String minValueSkipReason(String label, String rawValue, BigDecimal minimum) {
        if (!isPositive(minimum)) {
            return null;
        }
        if (!hasText(rawValue)) {
            return label + " is missing";
        }
        BigDecimal value = TradingMath.decimal(rawValue);
        if (value.compareTo(minimum) < 0) {
            return label + " is below configured minimum";
        }
        return null;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
