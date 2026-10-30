package com.trade.automation.infrastructure.config;

import com.trade.automation.application.task.AutomationTaskManager;
import com.trade.automation.application.task.AutomationTaskRegistrar;
import com.trade.automation.domain.model.AutomationTaskDefinition;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.x.XClientProperties;
import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import com.trade.polymarket.interfaces.scheduler.AiPolymarketScheduler;
import com.trade.story.infrastructure.config.AiStoryProperties;
import com.trade.story.interfaces.scheduler.AiStoryScheduler;
import com.trade.trading.application.runtime.TradingMarketDataRuntime;
import com.trade.trading.infrastructure.config.TradingAiProperties;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.interfaces.scheduler.TradingAiScheduler;
import com.trade.trading.interfaces.scheduler.TradingScheduler;
import com.trade.weibo.infrastructure.config.WeiboTelegramReviewProperties;
import com.trade.weibo.infrastructure.config.WeiboWorkflowProperties;
import com.trade.weibo.interfaces.scheduler.WeiboScheduler;
import com.trade.x.infrastructure.config.XPublishingProperties;
import com.trade.x.infrastructure.config.XTelegramReviewProperties;
import com.trade.x.infrastructure.config.XWorkflowProperties;
import com.trade.x.interfaces.scheduler.XScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeatureControlConfigurationTest {
    // Load the shipped YAML, including legacy env aliases, without host credentials or network access.
    private MockEnvironment environment() throws IOException {
        var environment = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        for (String variable : List.of("TRADING", "TRADING_AI", "POLYMARKET", "STORY", "WEIBO_WORKFLOW", "X_WORKFLOW")) {
            environment.withProperty("TRADE_" + variable + "_ENABLED", "false");
        }
        return environment;
    }

    @ParameterizedTest
    @CsvSource({"trading,TRADING", "trading-ai,TRADING_AI", "polymarket,POLYMARKET",
            "story,STORY", "weibo,WEIBO_WORKFLOW", "x,X_WORKFLOW"})
    void oneModuleSwitchStartsOnlyItsOwnTask(String taskId, String variable) throws IOException {
        var environment = environment().withProperty("TRADE_" + variable + "_ENABLED", "true");
        var definitions = definitions(environment);
        assertEquals(List.of(taskId), definitions.stream().filter(AutomationTaskDefinition::autoStart)
                .map(AutomationTaskDefinition::id).toList());

        environment.withProperty("TRADE_AUTOMATION_" + taskId.toUpperCase(java.util.Locale.ROOT).replace('-', '_')
                + "_AUTO_START", "false");
        assertTrue(definitions(environment).stream().noneMatch(AutomationTaskDefinition::autoStart));
    }

    @Test
    void disabledModulesStayStoppedAndLegacyExplicitStartRemainsAvailable() throws IOException {
        var environment = environment();
        assertTrue(definitions(environment).stream().noneMatch(AutomationTaskDefinition::autoStart));
        environment.withProperty("TRADE_AUTOMATION_STORY_AUTO_START", "true");
        assertEquals(List.of("story"), definitions(environment).stream().filter(AutomationTaskDefinition::autoStart)
                .map(AutomationTaskDefinition::id).toList());
        assertFalse(bind(environment, "trade.story", AiStoryProperties.class).isEnabled());
    }

    @Test
    void workflowSwitchEnablesNormalStagesButDoesNotAuthorizeLivePublishing() throws IOException {
        var environment = environment().withProperty("TRADE_X_WORKFLOW_ENABLED", "true")
                .withProperty("TRADE_X_TARGET_USER_ID", "123")
                .withProperty("TRADE_WEIBO_WORKFLOW_ENABLED", "true").withProperty("TRADE_WEIBO_TARGET_UID", "456");
        var x = bind(environment, "trade.x.workflow", XWorkflowProperties.class).policy();
        var weibo = bind(environment, "trade.weibo.workflow", WeiboWorkflowProperties.class).policy();
        assertDoesNotThrow(x::requireGeneration);
        assertDoesNotThrow(x::requirePublishing);
        assertDoesNotThrow(weibo::requireGeneration);
        assertDoesNotThrow(weibo::requirePublishing);
        assertFalse(bind(environment, "trade.x", XPublishingProperties.class).isLivePublishingEnabled());
        assertFalse(bind(environment, "trade.trading", TradingProperties.class).isLiveExecutionAllowed());
        environment.withProperty("TRADE_X_GENERATION_ENABLED", "false")
                .withProperty("TRADE_X_PUBLISHING_ENABLED", "false")
                .withProperty("TRADE_WEIBO_GENERATION_ENABLED", "false")
                .withProperty("TRADE_WEIBO_PUBLISHING_ENABLED", "false");
        var pausedX = bind(environment, "trade.x.workflow", XWorkflowProperties.class).policy();
        var pausedWeibo = bind(environment, "trade.weibo.workflow", WeiboWorkflowProperties.class).policy();
        assertThrows(IllegalStateException.class, pausedX::requireGeneration);
        assertThrows(IllegalStateException.class, pausedX::requirePublishing);
        assertThrows(IllegalStateException.class, pausedWeibo::requireGeneration);
        assertThrows(IllegalStateException.class, pausedWeibo::requirePublishing);
    }

    @Test
    void credentialsAndChatSelectTransportsWithExplicitPauseStillHonored() throws IOException {
        var environment = environment();
        assertFalse(bind(environment, "trade.telegram", TelegramClientProperties.class).isEnabled());
        assertFalse(bind(environment, "trade.x.client", XClientProperties.class).isEnabled());
        assertFalse(bind(environment, "trade.x.review.telegram", XTelegramReviewProperties.class).isEnabled());
        assertFalse(bind(environment, "trade.trading.ai", TradingAiProperties.class).getTelegram().isEnabled());
        environment.withProperty("TRADE_TELEGRAM_BOT_TOKEN", "offline-token")
                .withProperty("X_API_KEY", "offline-key").withProperty("X_API_SECRET", "offline-secret")
                .withProperty("X_ACCESS_TOKEN", "offline-access").withProperty("X_ACCESS_TOKEN_SECRET", "offline-secret")
                .withProperty("TRADE_X_TELEGRAM_CHAT_ID", "123")
                .withProperty("TRADE_WEIBO_TELEGRAM_CHAT_ID", "456")
                .withProperty("TRADE_TRADING_AI_TELEGRAM_CHAT_ID", "789")
                .withProperty("TRADE_TRADING_AI_TELEGRAM_BOT_TOKEN", "other-offline-token");
        assertTrue(bind(environment, "trade.telegram", TelegramClientProperties.class).isEnabled());
        assertTrue(bind(environment, "trade.x.client", XClientProperties.class).isEnabled());
        assertTrue(bind(environment, "trade.x.review.telegram", XTelegramReviewProperties.class).isEnabled());
        assertTrue(bind(environment, "trade.weibo.review.telegram", WeiboTelegramReviewProperties.class).isEnabled());
        var ai = bind(environment, "trade.trading.ai", TradingAiProperties.class);
        assertTrue(ai.getTelegram().isEnabled());
        assertTrue(ai.getTelegram().getClient().isEnabled());
        assertEquals("other-offline-token", ai.getTelegram().getClient().getBotToken());
        for (String variable : List.of("TELEGRAM", "X_CLIENT", "X_TELEGRAM_REVIEW", "WEIBO_TELEGRAM_REVIEW", "TRADING_AI_TELEGRAM")) {
            environment.withProperty("TRADE_" + variable + "_ENABLED", "false");
        }
        assertFalse(bind(environment, "trade.telegram", TelegramClientProperties.class).isEnabled());
        assertFalse(bind(environment, "trade.x.client", XClientProperties.class).isEnabled());
        assertFalse(bind(environment, "trade.x.review.telegram", XTelegramReviewProperties.class).isEnabled());
        assertFalse(bind(environment, "trade.weibo.review.telegram", WeiboTelegramReviewProperties.class).isEnabled());
        assertFalse(bind(environment, "trade.trading.ai", TradingAiProperties.class).getTelegram().isEnabled());
    }

    private List<AutomationTaskDefinition> definitions(MockEnvironment environment) {
        var manager = mock(AutomationTaskManager.class);
        var runtime = mock(TradingMarketDataRuntime.class);
        new AutomationTaskRegistrar(manager, bind(environment, "trade.automation", AutomationProperties.class),
                mock(TradingScheduler.class), bind(environment, "trade.trading", TradingProperties.class), runtime,
                mock(TradingAiScheduler.class), bind(environment, "trade.trading.ai", TradingAiProperties.class),
                mock(AiPolymarketScheduler.class), bind(environment, "trade.polymarket", AiPolymarketProperties.class),
                mock(AiStoryScheduler.class), bind(environment, "trade.story", AiStoryProperties.class),
                mock(WeiboScheduler.class), bind(environment, "trade.weibo.workflow", WeiboWorkflowProperties.class),
                bind(environment, "trade.weibo.review.telegram", WeiboTelegramReviewProperties.class),
                mock(XScheduler.class), bind(environment, "trade.x.workflow", XWorkflowProperties.class),
                bind(environment, "trade.x.review.telegram", XTelegramReviewProperties.class));
        var definitions = ArgumentCaptor.forClass(AutomationTaskDefinition.class);
        verify(manager, times(6)).register(definitions.capture());
        verifyNoInteractions(runtime);
        return definitions.getAllValues();
    }

    private <T> T bind(MockEnvironment environment, String prefix, Class<T> type) {
        return Binder.get(environment).bindOrCreate(prefix, Bindable.of(type));
    }
}
