package com.trade.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Data;

import java.util.List;

@Data
public class OrderBookLevel {
    private String px;
    private String sz;
    private String liquidatedOrders;
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
