package com.trade;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.trade.automation.application.task.AutomationTaskManager;
import com.trade.automation.domain.model.AutomationTaskStatus;
import com.trade.weibo.port.HumanReviewGateway;
import com.trade.weibo.infrastructure.review.UnavailableHumanReviewGateway;
import com.trade.x.port.XHumanReviewGateway;
import com.trade.x.infrastructure.review.UnavailableXReviewGateway;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "trade.trading.enabled=false",
        "trade.automation.trading.auto-start=false",
        "trade.automation.polymarket.auto-start=false",
        "trade.automation.weibo.auto-start=false",
        "trade.automation.x.auto-start=false",
        "trade.telegram.enabled=false",
        "trade.weibo.workflow.enabled=false",
        "trade.weibo.review.telegram.enabled=false",
        "trade.x.client.enabled=false",
        "trade.x.workflow.enabled=false",
        "trade.x.workflow.generation-enabled=false",
        "trade.x.workflow.publishing-enabled=false",
        "trade.x.live-publishing-enabled=false",
        "trade.x.review.telegram.enabled=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:trade_context;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
class TradeApplicationTests {
    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;
    @Autowired private AutomationTaskManager tasks;
    @Autowired private HumanReviewGateway reviews;
    @Autowired private XHumanReviewGateway xReviews;
    @Autowired private RequestMappingHandlerAdapter handlers;

    @Test
    void contextLoads() {
        assertEquals(List.of("polymarket", "trading", "trading-ai", "weibo", "x"),
                tasks.statuses().stream().map(AutomationTaskStatus::id).toList());
        assertInstanceOf(MappingJackson2HttpMessageConverter.class,
                handlers.getMessageConverters().stream()
                        .filter(converter -> converter.canWrite(AutomationTaskStatus.class, MediaType.APPLICATION_JSON))
                        .findFirst().orElseThrow());
        var weibo = tasks.status("weibo");
        assertFalse(weibo.running());
        assertEquals(3, weibo.loops().size());
        assertInstanceOf(UnavailableHumanReviewGateway.class, reviews);
        var x = tasks.status("x");
        assertFalse(x.running());
        assertEquals(3, x.loops().size());
        assertInstanceOf(UnavailableXReviewGateway.class, xReviews);
    }

    @Test
    void prometheusRegistryScrapesTradingMetrics() {
        String scrape = prometheusMeterRegistry.scrape();

        assertTrue(scrape.contains("trade_trading_events_queue_capacity"));
        assertTrue(scrape.contains("application=\"trade\""));
        assertTrue(scrape.contains("environment=\"local\""));
    }

}
