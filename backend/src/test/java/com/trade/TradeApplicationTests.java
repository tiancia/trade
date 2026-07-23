package com.trade;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "trade.trading.enabled=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:trade_context;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "trade.text-game.seed-enabled=false"
})
class TradeApplicationTests {
    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Test
    void contextLoads() {
    }

    @Test
    void prometheusRegistryScrapesTradingMetrics() {
        String scrape = prometheusMeterRegistry.scrape();

        assertTrue(scrape.contains("trade_trading_events_queue_capacity"));
        assertTrue(scrape.contains("application=\"trade\""));
        assertTrue(scrape.contains("environment=\"local\""));
    }

}
