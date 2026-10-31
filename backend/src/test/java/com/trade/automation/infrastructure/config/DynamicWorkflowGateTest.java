package com.trade.automation.infrastructure.config;

import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.client.x.XApi;
import com.trade.client.x.XClientProperties;
import com.trade.weibo.application.service.WeiboPostService;
import com.trade.weibo.application.service.WeiboPublishingService;
import com.trade.weibo.infrastructure.config.WeiboWorkflowProperties;
import com.trade.weibo.port.*;
import com.trade.x.application.service.XPostService;
import com.trade.x.infrastructure.config.XPublishingProperties;
import com.trade.x.infrastructure.config.XWorkflowProperties;
import com.trade.x.infrastructure.publisher.XApiPostPublisher;
import com.trade.x.port.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DynamicWorkflowGateTest {
    @Test void xReadsChangedModuleGateAtGenerationAndPublishingWithoutRecreatingServices() {
        var settings = new XWorkflowProperties(); settings.setTargetUserId("123");
        var posts = mock(XPostRepository.class);
        var service = new XPostService(posts, mock(XDraftGenerator.class), mock(XHumanReviewGateway.class),
                mock(XPostPublisher.class), settings.policy(), settings);
        assertTrue(service.runGeneration()); verifyNoInteractions(posts);
        settings.setEnabled(true); assertTrue(service.runGeneration());
        verify(posts).reserveGeneration(any(), any(), anyInt());
        clearInvocations(posts); settings.setEnabled(false); assertTrue(service.runGeneration()); verifyNoInteractions(posts);

        var client = new XClientProperties(); client.setEnabled(true);
        var publisher = new XApiPostPublisher(mock(XApi.class), client, new XPublishingProperties(), settings);
        assertEquals("X workflow is disabled", publisher.unavailableReason("123"));
        settings.setEnabled(true);
        assertEquals("X live publishing is disabled", publisher.unavailableReason("123"));
        settings.setEnabled(false);
        assertEquals("X workflow is disabled", publisher.unavailableReason("123"));
    }

    @Test void weiboReadsChangedModuleGateAndStillRequiresLiveAuthorization() {
        var settings = new WeiboWorkflowProperties(); settings.setTargetUid("456");
        var source = mock(HotEventSource.class);
        var service = new WeiboPostService(mock(WeiboPostRepository.class), source, mock(WeiboDraftGenerator.class),
                mock(HumanReviewGateway.class), mock(WeiboPostPublisher.class), settings);
        assertTrue(service.runGeneration()); verifyNoInteractions(source);
        settings.setEnabled(true); assertTrue(service.runGeneration()); verify(source).collect();
        clearInvocations(source); settings.setEnabled(false); assertTrue(service.runGeneration()); verifyNoInteractions(source);
        var api = mock(WeiboApi.class);
        var publisher = new WeiboPublishingService(api, mock(WeiboAccountTokenRepository.class), new WeiboClientProperties(), settings);
        settings.setEnabled(true);
        assertThrows(RuntimeException.class, () -> publisher.publishText("test"));
        verifyNoInteractions(api);
    }
}
