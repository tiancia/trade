package com.trade.trading.port;

public interface TradingAiNotifier {
    boolean enabled();
    void validateConfiguration();
    void send(String message);
}
