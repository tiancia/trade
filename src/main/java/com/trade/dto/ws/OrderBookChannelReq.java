package com.trade.dto.ws;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class OrderBookChannelReq {
    private String channel = "books";
    private String instId;

    /**
     * Converts this typed request to the generic OKX WebSocket channel argument.
     */
    public OkxWsArg toArg() {
        return new OkxWsArg()
                .setChannel(channel)
                .setInstId(instId);
    }
}
