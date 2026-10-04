package com.trade.x.infrastructure.config;

import com.trade.automation.infrastructure.config.AutomationConfiguration;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.telegram.config.TelegramClientConfiguration;
import com.trade.weibo.infrastructure.config.WeiboTelegramReviewProperties;
import com.trade.x.application.port.*;
import com.trade.x.domain.model.XWorkflowPolicy;
import com.trade.x.infrastructure.review.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XConfigurationTest {
    private final XReviewDeliveryStore store = mock(XReviewDeliveryStore.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(XConfiguration.class, TelegramClientConfiguration.class)
            .withBean(XPostRepository.class, () -> mock(XPostRepository.class))
            .withBean(XDraftGenerator.class, () -> mock(XDraftGenerator.class))
            .withBean(XPostPublisher.class, () -> mock(XPostPublisher.class))
            .withBean(XReviewDeliveryStore.class, () -> store);

    @Test void defaultsHaveNoEnabledSideEffectsOrRequiredCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(XHumanReviewGateway.class);
            assertInstanceOf(UnavailableXReviewGateway.class, context.getBean(XHumanReviewGateway.class));
            XWorkflowPolicy policy = context.getBean(XWorkflowPolicy.class);
            assertFalse(policy.enabled()); assertFalse(policy.generationEnabled()); assertFalse(policy.publishingEnabled());
            assertFalse(context.getBean(XPublishingProperties.class).isLivePublishingEnabled());
            assertEquals(40, policy.content().minChars()); assertEquals(120, policy.content().maxChars());
            verifyNoInteractions(store);
        });
    }

    @Test void directionStyleAndLengthBindIntoFrozenDomainPolicy() {
        runner.withPropertyValues("trade.x.workflow.enabled=true", "trade.x.workflow.target-user-id=123",
                "trade.x.workflow.generation-enabled=true", "trade.x.workflow.content.direction=开发经验",
                "trade.x.workflow.content.language=English", "trade.x.workflow.content.tone=concise",
                "trade.x.workflow.content.instructions=no hashtags", "trade.x.workflow.content.min-chars=30",
                "trade.x.workflow.content.max-chars=100").run(context -> {
            assertThat(context).hasNotFailed(); var content = context.getBean(XWorkflowPolicy.class).content();
            assertEquals("开发经验", content.direction()); assertEquals("English", content.language());
            assertEquals("concise", content.tone()); assertEquals("no hashtags", content.instructions());
            assertEquals(30, content.minChars()); assertEquals(100, content.maxChars());
            verifyNoInteractions(store);
        });
    }

    @Test void enabledReviewBindsAllowlistAndNeedsNoStartupNetwork() {
        runner.withPropertyValues("trade.x.workflow.enabled=true", "trade.x.workflow.target-user-id=123",
                "trade.telegram.enabled=true", "trade.telegram.bot-token=offline-token",
                "trade.x.review.telegram.enabled=true", "trade.x.review.telegram.chat-id=-100123",
                "trade.x.review.telegram.reviewer-user-ids=42,43").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(XHumanReviewGateway.class);
            assertInstanceOf(TelegramXReviewGateway.class, context.getBean(XHumanReviewGateway.class));
            assertEquals(List.of(42L, 43L), context.getBean(XTelegramReviewProperties.class).getReviewerUserIds());
            verifyNoInteractions(store);
        });
    }

    @Test void incompleteAndInvalidSettingsFailBeforeAnyProviderCall() {
        runner.withPropertyValues("trade.x.workflow.enabled=true", "trade.x.workflow.target-user-id=123",
                "trade.x.workflow.generation-enabled=true").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("trade.x.review.telegram.enabled=true").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("trade.x.workflow.content.min-chars=100", "trade.x.workflow.content.max-chars=20")
                .run(context -> assertThat(context).hasFailed());
        verifyNoInteractions(store);
    }

    @Test void sharedBotRejectsTwoReviewConsumersWhileEitherAloneCanStart() {
        var weibo = new WeiboTelegramReviewProperties(); var x = new XTelegramReviewProperties();
        var both = new ApplicationContextRunner().withUserConfiguration(AutomationConfiguration.class)
                .withBean(WeiboTelegramReviewProperties.class, () -> weibo)
                .withBean(XTelegramReviewProperties.class, () -> x);
        x.setEnabled(true);
        both.run(context -> assertThat(context).hasNotFailed());
        weibo.setEnabled(true);
        both.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Weibo and X share one Telegram bot: disable trade.weibo.review.telegram.enabled before enabling X review");
        });
        x.setEnabled(false); both.run(context -> assertThat(context).hasNotFailed());
    }
}
