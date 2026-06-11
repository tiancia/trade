package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Data;

import java.util.List;

/** One price level in an OKX order-book snapshot. */
@Data
public class OrderBookLevel {
    /** Price at this level. */
    private String px;
    /** Aggregate size at this level. */
    private String sz;
    /** Number of liquidated orders; retained for OKX array compatibility. */
    private String liquidatedOrders;
    /** Number of orders represented by the level. */
    private String orders;

    public OrderBookLevel() {
    }

    /**
     * Maps OKX order book level arrays: [price, size, liquidatedOrders, orders].
     */
    @JsonCreator
    public OrderBookLevel(List<String> values) {
        this.px = get(values, 0);
        this.sz = get(values, 1);
        this.liquidatedOrders = get(values, 2);
        this.orders = get(values, 3);
    }

    private static String get(List<String> values, int index) {
        return values != null && values.size() > index ? values.get(index) : null;
    }
}
