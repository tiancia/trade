package com.trade.trading.application.port;

public interface TradingAiNotifier {
    boolean enabled();
    void validateConfiguration();
    void send(String message);
}
