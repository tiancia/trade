package com.trade.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TickerReq {
    private String instId;
}
