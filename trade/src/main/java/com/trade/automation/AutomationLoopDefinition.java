package com.trade.automation;

import java.time.Duration;

public record AutomationLoopDefinition(
        String id,
        Duration initialDelay,
        Duration fixedDelay,
        Runnable action
) {
}
