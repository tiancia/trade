package com.trade.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class CancelAllAfterReq {
    private String timeOut;
}
