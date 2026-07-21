package com.trade.trading.web;

import com.trade.trading.market.CandleSeries;
import com.trade.trading.market.TradingMarketDataQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Browser-facing recent and streaming K-line API. */
@RestController
@RequestMapping("/api/trading/market/candles")
public class TradingMarketDataController {
    private final TradingMarketDataQueryService marketDataQueryService;
    private final TradingCandleStream candleStream;

    public TradingMarketDataController(
            TradingMarketDataQueryService marketDataQueryService,
            TradingCandleStream candleStream
    ) {
        this.marketDataQueryService = marketDataQueryService;
        this.candleStream = candleStream;
    }

    @GetMapping
    public CandleSeries candles(
            @RequestParam(defaultValue = "BTC-USDT") String instId,
            @RequestParam(defaultValue = "1m") String bar,
            @RequestParam(defaultValue = "80") int limit
    ) {
        try {
            return marketDataQueryService.recentCandles(instId, bar, limit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(defaultValue = "BTC-USDT") String instId,
            @RequestParam(defaultValue = "1m") String bar,
            @RequestParam(defaultValue = "80") int limit
    ) {
        try {
            return candleStream.subscribe(marketDataQueryService.recentCandles(instId, bar, limit));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
