package com.trade.automation.infrastructure.config;

import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.weibo.infrastructure.config.WeiboTelegramReviewProperties;
import com.trade.weibo.infrastructure.config.WeiboWorkflowProperties;
import com.trade.x.infrastructure.config.XPublishingProperties;
import com.trade.x.infrastructure.config.XTelegramReviewProperties;
import com.trade.x.infrastructure.config.XWorkflowProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModuleControlConfigurationTest {
    @Test void activationValidatesMissingAccountAndSharedTelegramConsumerWithoutChangingLiveAuthorization() {
        var config = new ModuleControlConfiguration();
        var x = new XWorkflowProperties(); var weibo = new WeiboWorkflowProperties();
        var xr = new XTelegramReviewProperties(); var wr = new WeiboTelegramReviewProperties();
        var telegram = new TelegramClientProperties(); var publishing = new XPublishingProperties();
        var control = config.xControl(x, xr, telegram, publishing, weibo, wr);
        assertThrows(IllegalArgumentException.class, control.validateEnable()::run);
        assertFalse(x.isEnabled()); assertFalse(publishing.isLivePublishingEnabled());
        x.setTargetUserId("123");
        assertDoesNotThrow(control.validateEnable()::run);
        telegram.setEnabled(true); telegram.setBotToken("offline-test-token");
        xr.setEnabled(true); xr.setChatId("123"); xr.setReviewerUserIds(List.of(123L));
        wr.setEnabled(true); weibo.setEnabled(true);
        assertThrows(IllegalStateException.class, control.validateEnable()::run);
        weibo.setEnabled(false);
        assertDoesNotThrow(control.validateEnable()::run);
        control.changeEnabled().accept(true);
        assertTrue(x.isEnabled()); assertFalse(publishing.isLivePublishingEnabled());
    }

    @Test void weiboActivationRetainsReviewMessageLengthGuard() {
        var properties = new WeiboWorkflowProperties(); properties.setTargetUid("456"); properties.setMaxBodyChars(3001);
        var review = new WeiboTelegramReviewProperties(); review.setEnabled(true); review.setChatId("456"); review.setReviewerUserIds(List.of(123L));
        var telegram = new TelegramClientProperties(); telegram.setEnabled(true); telegram.setBotToken("offline-token");
        var control = new ModuleControlConfiguration().weiboControl(properties, review, telegram,
                new WeiboClientProperties(), new XWorkflowProperties(), new XTelegramReviewProperties());
        assertThrows(IllegalArgumentException.class, control.validateEnable()::run);
        assertFalse(properties.isEnabled());
    }
}
