package com.trade.weibo.infrastructure.config;

import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.telegram.config.TelegramClientConfiguration;
import com.trade.weibo.application.port.HumanReviewGateway;
import com.trade.weibo.application.port.WeiboReviewDeliveryStore;
import com.trade.weibo.infrastructure.review.TelegramHumanReviewGateway;
import com.trade.weibo.infrastructure.review.UnavailableHumanReviewGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WeiboTelegramReviewPropertiesTest {
    private final WeiboReviewDeliveryStore store = mock(WeiboReviewDeliveryStore.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WeiboConfiguration.class, TelegramClientConfiguration.class)
            .withBean(WeiboReviewDeliveryStore.class, () -> store);

    @Test
    void defaultContextNeedsNoCredentialsAndBindsOnlyUnavailableGateway() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(HumanReviewGateway.class);
            assertThat(context.getBean(HumanReviewGateway.class)).isInstanceOf(UnavailableHumanReviewGateway.class);
            context.getBean(HumanReviewGateway.class).poll(decision -> { throw new AssertionError("No review expected"); });
            assertThat(context.getBean(TelegramClientProperties.class).isEnabled()).isFalse();
            verifyNoInteractions(store);
        });
    }

    @Test
    void explicitSettingsBindNumericAllowlistAndSelectTelegramWithoutNetworkAccess() {
        runner.withPropertyValues("trade.weibo.workflow.enabled=true", "trade.telegram.enabled=true",
                "trade.telegram.bot-token=offline-test-token", "trade.weibo.review.telegram.enabled=true",
                "trade.weibo.review.telegram.chat-id=-100123", "trade.weibo.review.telegram.reviewer-user-ids=123,456")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(HumanReviewGateway.class);
                    assertThat(context.getBean(HumanReviewGateway.class)).isInstanceOf(TelegramHumanReviewGateway.class);
                    assertThat(context.getBean(WeiboTelegramReviewProperties.class).getReviewerUserIds())
                            .containsExactly(123L, 456L);
                    verifyNoInteractions(store);
                });
    }

    @Test
    void incompleteReviewConfigurationFailsClosed() {
        runner.withPropertyValues("trade.weibo.review.telegram.enabled=true").run(context -> assertThat(context).hasFailed());
        TelegramClientProperties client = new TelegramClientProperties();
        client.setEnabled(true);
        client.setBotToken("offline-test-token");
        WeiboWorkflowProperties workflow = new WeiboWorkflowProperties();
        workflow.setEnabled(true);
        WeiboTelegramReviewProperties settings = new WeiboTelegramReviewProperties();
        settings.setEnabled(true);
        settings.setChatId("@untrusted-name");
        settings.setReviewerUserIds(List.of(123L));
        assertThrows(IllegalArgumentException.class, () -> settings.validate(client, workflow));
        settings.setChatId("123");
        settings.setReviewerUserIds(List.of());
        assertThrows(IllegalArgumentException.class, () -> settings.validate(client, workflow));
        settings.setReviewerUserIds(List.of(123L));
        workflow.setMaxBodyChars(10000);
        assertThrows(IllegalArgumentException.class, () -> settings.validate(client, workflow));
    }
}
