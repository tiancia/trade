package com.trade;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "trade.trading.enabled=false")
class TradeApplicationTests {

    @Test
    void contextLoads() {
    }

}
