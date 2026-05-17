package com.trade.client.okx;

import com.trade.client.okx.dto.OkxResponse;

public interface OkxRestClient {
    <T> OkxResponse<T> get(String path, Object req, boolean needAuth, Class<T> dataClass);

    <T> OkxResponse<T> post(String path, Object req, boolean needAuth, Class<T> dataClass);
}
