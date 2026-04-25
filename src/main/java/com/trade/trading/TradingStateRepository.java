package com.trade.trading;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Component
public class TradingStateRepository {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final Path statePath;
    private TradingState state;

    @Autowired
    public TradingStateRepository(AiTradingProperties properties) {
        this(Path.of(properties.getStateFile()));
    }

    TradingStateRepository(Path statePath) {
        this.statePath = statePath;
    }

    public synchronized TradingState getState() {
        if (state == null) {
            state = readState();
        }
        return copy(state);
    }

    public synchronized void recordBuy(BigDecimal baseAmount, BigDecimal price) {
        if (baseAmount == null || price == null || baseAmount.signum() <= 0 || price.signum() <= 0) {
            return;
        }

        TradingState current = state == null ? readState() : state;
        BigDecimal oldBase = nullToZero(current.getTrackedBaseAmount());
        BigDecimal oldCost = nullToZero(current.getAverageCost());
        BigDecimal newBase = oldBase.add(baseAmount);
        BigDecimal newCost = oldBase.multiply(oldCost)
                .add(baseAmount.multiply(price))
                .divide(newBase, 18, java.math.RoundingMode.HALF_UP);

        state = new TradingState()
                .setTrackedBaseAmount(newBase)
                .setAverageCost(newCost)
                .setUpdatedAt(Instant.now().toString());
        writeState(state);
    }

    public synchronized void recordSell(BigDecimal baseAmount) {
        if (baseAmount == null || baseAmount.signum() <= 0) {
            return;
        }

        TradingState current = state == null ? readState() : state;
        BigDecimal oldBase = nullToZero(current.getTrackedBaseAmount());
        BigDecimal remaining = oldBase.subtract(baseAmount);
        if (remaining.signum() <= 0) {
            remaining = BigDecimal.ZERO;
        }

        BigDecimal averageCost = remaining.signum() > 0
                ? nullToZero(current.getAverageCost())
                : BigDecimal.ZERO;
        state = new TradingState()
                .setTrackedBaseAmount(remaining)
                .setAverageCost(averageCost)
                .setUpdatedAt(Instant.now().toString());
        writeState(state);
    }

    private TradingState readState() {
        if (!Files.exists(statePath)) {
            return new TradingState().setUpdatedAt(Instant.now().toString());
        }

        try {
            TradingState loaded = objectMapper.readValue(statePath.toFile(), TradingState.class);
            if (loaded.getTrackedBaseAmount() == null) {
                loaded.setTrackedBaseAmount(BigDecimal.ZERO);
            }
            if (loaded.getAverageCost() == null) {
                loaded.setAverageCost(BigDecimal.ZERO);
            }
            return loaded;
        } catch (Exception e) {
            throw new IllegalStateException("Read trading state failed: " + statePath, e);
        }
    }

    private void writeState(TradingState nextState) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(statePath.toFile(), nextState);
        } catch (Exception e) {
            throw new IllegalStateException("Write trading state failed: " + statePath, e);
        }
    }

    private static TradingState copy(TradingState source) {
        return new TradingState()
                .setTrackedBaseAmount(nullToZero(source.getTrackedBaseAmount()))
                .setAverageCost(nullToZero(source.getAverageCost()))
                .setUpdatedAt(source.getUpdatedAt());
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
