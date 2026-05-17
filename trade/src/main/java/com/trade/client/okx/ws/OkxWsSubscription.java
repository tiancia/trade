package com.trade.client.okx.ws;

public interface OkxWsSubscription extends AutoCloseable {
    void unsubscribe();

    @Override
    void close();
}
