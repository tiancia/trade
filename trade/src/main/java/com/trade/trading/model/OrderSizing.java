package com.trade.trading.model;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class OrderSizing {
    private boolean executable;
    private String size;
    private String skipReason;

    public static OrderSizing executable(String size) {
        return new OrderSizing().setExecutable(true).setSize(size);
    }

    public static OrderSizing skipped(String reason) {
        return new OrderSizing().setExecutable(false).setSkipReason(reason);
    }
}
