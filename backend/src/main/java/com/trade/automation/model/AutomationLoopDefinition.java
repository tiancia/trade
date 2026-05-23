package com.trade.automation.model;

import java.time.Duration;

public record AutomationLoopDefinition(
        String id,
        Duration initialDelay,
        Duration fixedDelay,
        Runnable action
) {
}
