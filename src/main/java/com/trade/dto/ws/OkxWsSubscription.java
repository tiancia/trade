package com.trade.dto.ws;

public interface OkxWsSubscription extends AutoCloseable {
    void unsubscribe();

    @Override
    void close();
}
