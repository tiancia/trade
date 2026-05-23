package com.trade.client.okx.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PositionsReq {
    private String instType;
    private String instId;
    private String posId;
}
