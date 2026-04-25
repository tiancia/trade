package com.trade.trading;

import com.trade.common.CommonResponse;

import java.util.List;
import java.util.Optional;

final class OkxResponses {
    private OkxResponses() {
    }

    static <T> Optional<T> first(CommonResponse<T> response) {
        if (response == null) {
            return Optional.empty();
        }
        List<T> data = response.getData();
        if (data == null || data.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(data.get(0));
    }

    static <T> T requireFirst(CommonResponse<T> response, String name) {
        return first(response)
                .orElseThrow(() -> new IllegalStateException("OKX response has no " + name + " data"));
    }
}
