package com.trade.client.okx.ws;

public interface OkxWsListener<T> {
    default void onEvent(OkxWsEvent<T> event) {
    }

    default void onData(OkxWsEvent<T> event) {
    }

    default void onError(Throwable error) {
    }

    default void onClose(int statusCode, String reason) {
    }
}
