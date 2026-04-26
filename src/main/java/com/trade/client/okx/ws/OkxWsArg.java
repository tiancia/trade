package com.trade.client.okx.ws;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OkxWsArg {
    private String channel;
    private String instType;
    private String instId;
    private String ccy;
}
