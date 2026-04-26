package com.trade.trading.service;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.AiTradingProperties;
import com.trade.trading.model.TradingEvent;
import com.trade.trading.model.TradingState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingEventDetectorTest {

    @Test
    void detectsPriceMoveVolumeSpikeAndFloatingLoss() {
        AiTradingProperties properties = new AiTradingProperties();
        TradingEventDetector detector = new TradingEventDetector(properties);
        TickerResp ticker = new TickerResp();
        ticker.setLast("89");

        List<CandleResp> candles = new java.util.ArrayList<>();
        candles.add(candle("100", "3000"));
        for (int i = 0; i < 3; i++) {
            candles.add(candle("95", "100"));
        }
        candles.add(candle("100", "100"));
        for (int i = 0; i < 16; i++) {
            candles.add(candle("100", "100"));
        }

        TradingState state = new TradingState()
                .setTrackedBaseAmount(new BigDecimal("0.1"))
                .setAverageCost(new BigDecimal("100"));

        List<TradingEvent> events = detector.detect(ticker, candles, state);

        assertEquals(3, events.size());
        assertTrue(events.stream().anyMatch(event -> event.type().equals("PRICE_MOVE_5M")));
        assertTrue(events.stream().anyMatch(event -> event.type().equals("VOLUME_SPIKE")));
        assertTrue(events.stream().anyMatch(event -> event.type().equals("FLOATING_LOSS")));
    }

    private static CandleResp candle(String close, String quoteVolume) {
        CandleResp candle = new CandleResp();
        candle.setClose(close);
        candle.setVolCcyQuote(quoteVolume);
        candle.setConfirm("1");
        return candle;
    }
}
