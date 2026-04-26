package com.trade.client.okx.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class CandlesReq {
    private String instId;
    private String after;
    private String before;
    private String bar;
    private String limit;
}
