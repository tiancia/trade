package com.trade.weibo.infrastructure.config;

import com.trade.client.weibo.WeiboClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WeiboWorkflowPropertiesTest {
    @Test
    void defaultsAndBindingKeepEveryExternalActionOff() {
        var defaults = new WeiboWorkflowProperties().policy();
        assertFalse(defaults.enabled());
        assertFalse(defaults.generationEnabled());
        assertFalse(defaults.publishingEnabled());
        assertFalse(new WeiboClientProperties().isLivePublishingEnabled());
        assertTrue(new WeiboClientProperties().isReviewRequired());
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "trade.weibo.workflow.enabled", "true", "trade.weibo.workflow.target-uid", "uid",
                "trade.weibo.workflow.daily-generation-limit", "2")));
        var bound = binder.bind("trade.weibo.workflow", Bindable.of(WeiboWorkflowProperties.class)).get();
        assertTrue(bound.policy().enabled());
        assertEquals("uid", bound.policy().targetUid());
        assertEquals(2, bound.policy().dailyGenerationLimit());
        assertFalse(bound.policy().publishingEnabled());
    }

    @Test
    void nonsensicalLimitsFailBeforeTasksStart() {
        var properties = new WeiboWorkflowProperties();
        properties.setPublishingFixedDelayMs(0);
        assertThrows(IllegalArgumentException.class, properties::policy);
        properties.setPublishingFixedDelayMs(60000);
        properties.setClaimTimeoutSeconds(1);
        assertThrows(IllegalArgumentException.class, properties::policy);
    }
}
