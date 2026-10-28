package com.trade.trading.infrastructure.config;

import com.trade.client.telegram.TelegramClientProperties;
import com.trade.trading.infrastructure.notification.TradingAiTelegramNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TradingAiPropertiesTest {
    @Test void disabledDefaultsNeedNoCredentialsAndBindingKeepsTransportIndependent() {
        var defaults = new TradingAiProperties(); defaults.validate();
        assertFalse(defaults.isEnabled()); assertFalse(defaults.isAutoApply());
        assertFalse(defaults.getTelegram().isEnabled());
        assertEquals(86400000, defaults.getReviewIntervalMs());
        assertEquals(5, defaults.getTargetMinClosedTradesPerDay());
        assertEquals(8, defaults.getTargetMaxClosedTradesPerDay());
        var bound = new Binder(new MapConfigurationPropertySource(Map.of(
                "trade.trading.ai.enabled", "true", "trade.trading.ai.auto-apply", "true",
                "trade.trading.ai.telegram.chat-id", "12345",
                "trade.trading.ai.telegram.client.bot-token", "222:test",
                "trade.trading.ai.telegram.client.proxy.enabled", "true")))
                .bind("trade.trading.ai", Bindable.of(TradingAiProperties.class)).get();
        assertTrue(bound.isAutoApply()); assertEquals("12345", bound.getTelegram().getChatId());
        assertEquals("222:test", bound.getTelegram().getClient().getBotToken());
        assertTrue(bound.getTelegram().getClient().getProxy().isEnabled());
        defaults.setReviewIntervalMs(1000); assertThrows(IllegalArgumentException.class, defaults::validate);
    }

    @Test void bindsDailyTargetsAndRejectsInvertedOrUnboundedRanges() {
        var bound = new Binder(new MapConfigurationPropertySource(Map.of(
                "trade.trading.ai.target-min-closed-trades-per-day", "6",
                "trade.trading.ai.target-max-closed-trades-per-day", "9")))
                .bind("trade.trading.ai", Bindable.of(TradingAiProperties.class)).get();
        bound.validate();
        assertEquals(6, bound.getTargetMinClosedTradesPerDay());
        assertEquals(9, bound.getTargetMaxClosedTradesPerDay());
        bound.setTargetMinClosedTradesPerDay(10);
        assertThrows(IllegalArgumentException.class, bound::validate);
        bound.setTargetMinClosedTradesPerDay(0);
        assertThrows(IllegalArgumentException.class, bound::validate);
        bound.setTargetMinClosedTradesPerDay(5); bound.setTargetMaxClosedTradesPerDay(101);
        assertThrows(IllegalArgumentException.class, bound::validate);
    }

    @Test void newPrivateBotMayUseSameUserIdButCannotReuseSharedBotOrXGroup() {
        var ai = new TradingAiProperties();
        ai.getTelegram().setEnabled(true); ai.getTelegram().setChatId("12345");
        ai.getTelegram().getClient().setEnabled(true);
        ai.getTelegram().getClient().setBotToken("222:test");
        var shared = new TelegramClientProperties(); shared.setBotToken("111:shared-test");
        assertDoesNotThrow(() -> new TradingAiTelegramNotifier(ai, shared, "12345").validateConfiguration());
        ai.getTelegram().getClient().setBotToken("111:rotated-test");
        assertThrows(IllegalArgumentException.class, () -> new TradingAiTelegramNotifier(ai, shared, "12345").validateConfiguration());
        ai.getTelegram().getClient().setBotToken("222:test");
        ai.getTelegram().setChatId("-10012345");
        assertThrows(IllegalArgumentException.class, () -> new TradingAiTelegramNotifier(ai, shared, "-10012345").validateConfiguration());
        assertDoesNotThrow(() -> new TradingAiTelegramNotifier(ai, shared, "-1009999").validateConfiguration());
    }
}
